package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ProximityBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** A direct peer's proximity from RSSI samples (ADR 0007 decision 10, ticket 32 Q5 and Q6). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximitiesTest {
    @Test
    fun firstSampleIsTakenAsIsAndBucketedAtTheEdges() =
        runTest {
            val proximities = proximities()
            val samples = listOf(-55, -56, -75, -76)
            samples.forEach { rssi ->
                proximities.linked(id(rssi), Handle(), null)
                proximities.sighted(Handle(), id(rssi), rssi)
            }

            val read = samples.map { proximities.of(id(it)) }

            assertEquals(samples.map { it.toDouble() }, read.map { it.rssi })
            val buckets = read.map { it.bucket }
            assertEquals(
                listOf(ProximityBucket.Immediate, ProximityBucket.Near, ProximityBucket.Near, ProximityBucket.Far),
                buckets,
            )
            assertEquals(Instant.fromEpochMilliseconds(START_MILLIS), read.first().measuredAt)
        }

    @Test
    fun laterSamplesAreSmoothed() =
        runTest {
            val proximities = proximities()
            val handle = Handle()
            proximities.linked(ID, handle, null)

            proximities.sighted(handle, null, -60)
            proximities.sighted(handle, null, -80)

            assertEquals(-65.0, proximities.of(ID).rssi)
        }

    @Test
    fun aPeerWithoutSamplesIsUnknown() =
        runTest {
            val proximities = proximities()
            proximities.linked(ID, Handle(), null)

            val proximity = proximities.of(ID)

            assertNull(proximity.rssi)
            assertEquals(ProximityBucket.Unknown, proximity.bucket)
            assertNull(proximity.measuredAt)
        }

    @Test
    fun aSampleOlderThanThirtySecondsIsUnknownAndRepublishedAtThatMoment() =
        runTest {
            var changes = 0
            val proximities = proximities { changes++ }
            val handle = Handle()
            proximities.linked(ID, handle, null)
            proximities.sighted(handle, null, -60)

            advanceTimeBy(STALE_MILLIS - 1)
            runCurrent()
            val before = proximities.of(ID).bucket to changes
            advanceTimeBy(1)
            runCurrent()

            assertEquals(ProximityBucket.Near to 1, before)
            assertEquals(ProximityBucket.Unknown, proximities.of(ID).bucket)
            assertEquals(-60.0, proximities.of(ID).rssi)
            assertEquals(2, changes)
        }

    @Test
    fun aSampleAfterAStaleOneRestartsSmoothing() =
        runTest {
            val proximities = proximities()
            val handle = Handle()
            proximities.linked(ID, handle, null)
            proximities.sighted(handle, null, -60)
            advanceTimeBy(STALE_MILLIS)

            proximities.sighted(handle, null, -80)

            assertEquals(-80.0, proximities.of(ID).rssi)
        }

    @Test
    fun onlyASightingThatChangesTheBucketRepublishes() =
        runTest {
            var changes = 0
            val proximities = proximities { changes++ }
            val handle = Handle()
            proximities.linked(ID, handle, null)

            val counts =
                listOf(-60, -62, -100, -120).map {
                    proximities.sighted(handle, null, it)
                    changes
                }

            assertEquals(listOf(1, 1, 1, 2), counts)
        }

    @Test
    fun aSightingCountsForTheLinkAtItsAddressOrItsResolvedPeer() =
        runTest {
            val proximities = proximities()
            val handle = Handle()
            proximities.linked(ID, handle, null)

            proximities.sighted(Handle(), null, -40)
            val unmatched = proximities.of(ID).rssi
            proximities.sighted(Handle(), NodeId(ByteArray(ID_SIZE)), -40)
            val otherPeer = proximities.of(ID).rssi
            proximities.sighted(Handle(), ID, -60)
            val byId = proximities.of(ID).rssi
            proximities.sighted(handle, null, -80)

            assertNull(unmatched)
            assertNull(otherPeer)
            assertEquals(-60.0, byId)
            assertEquals(-65.0, proximities.of(ID).rssi)
        }

    @Test
    fun aCentralLinkPollsEveryTenSecondsAndRepublishesEachReading() =
        runTest {
            var changes = 0
            val proximities = proximities { changes++ }
            val readings =
                ArrayDeque<RadioResult<Int>>().apply {
                    add(RadioResult.Ok(-50))
                    add(RadioResult.Failed(RadioFailure.LINK_CLOSED))
                    add(RadioResult.Ok(-70))
                }
            var reads = 0
            proximities.linked(ID, Handle()) {
                reads++
                readings.removeFirstOrNull() ?: RadioResult.Failed(RadioFailure.UNSUPPORTED)
            }

            advanceTimeBy(POLL_MILLIS - 1)
            runCurrent()
            val early = reads
            advanceTimeBy(1)
            runCurrent()
            val first = proximities.of(ID).rssi to changes
            advanceTimeBy(2 * POLL_MILLIS)
            runCurrent()

            assertEquals(0, early)
            assertEquals(-50.0 to 1, first)
            assertEquals(-55.0, proximities.of(ID).rssi)
            assertEquals(2, changes)
            advanceTimeBy(10 * POLL_MILLIS)
            assertEquals(4, reads)
        }

    @Test
    fun anUnlinkedOrReplacedLinkStopsPolling() =
        runTest {
            val proximities = proximities()
            var replacedReads = 0
            var keptReads = 0
            proximities.linked(ID, Handle()) { RadioResult.Ok(-50).also { replacedReads++ } }
            proximities.linked(ID, Handle()) { RadioResult.Ok(-50).also { keptReads++ } }
            advanceTimeBy(POLL_MILLIS + 1)

            proximities.unlinked(ID)
            advanceTimeBy(10 * POLL_MILLIS)

            assertEquals(0, replacedReads)
            assertEquals(1, keptReads)
        }

    @Test
    fun unlinkingAPeerThatWasSightedCancelsItsExpiry() =
        runTest {
            var changes = 0
            val proximities = proximities { changes++ }
            val handle = Handle()
            proximities.linked(ID, handle, null)
            proximities.sighted(handle, null, -60)

            proximities.unlinked(ID)
            advanceTimeBy(2 * STALE_MILLIS)

            assertEquals(1, changes)
        }

    private fun TestScope.proximities(onChange: () -> Unit = {}): Proximities {
        val clock = SchedulerClock(this)
        return Proximities(clock, backgroundScope, onChange)
    }

    private class Handle : PeerHandle

    private class SchedulerClock(
        private val scope: TestScope,
    ) : Clock {
        override fun monotonicMillis(): Long = scope.testScheduler.currentTime

        override fun wallClockMillis(): Long = START_MILLIS + scope.testScheduler.currentTime
    }

    private companion object {
        const val START_MILLIS = 1_767_225_600_000
        const val STALE_MILLIS = 30_000L
        const val POLL_MILLIS = 10_000L
        const val ID_SIZE = 32
        val ID = NodeId(ByteArray(ID_SIZE) { 1 })

        fun id(rssi: Int) = NodeId(ByteArray(ID_SIZE) { rssi.toByte() })
    }
}
