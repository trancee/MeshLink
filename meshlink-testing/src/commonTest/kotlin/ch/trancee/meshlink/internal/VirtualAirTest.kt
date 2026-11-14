package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualAirTest {
    @Test
    fun twoRadiosSeeEachOthersAdvertisement() =
        runTest {
            val air = air()
            val a = air.radio()
            val b = air.radio()

            a.startScanning().ok()
            b.startAdvertising(PAYLOAD_B).ok()
            a.startAdvertising(PAYLOAD_A).ok()
            b.startScanning().ok()

            val seenByA = a.events.next<RadioEvent.Sighted>().sighting
            val seenByB = b.events.next<RadioEvent.Sighted>().sighting
            assertContentEquals(PAYLOAD_B, seenByA.payload)
            assertSame(b.handle, seenByA.peer)
            assertContentEquals(PAYLOAD_A, seenByB.payload)
            assertSame(a.handle, seenByB.peer)
            assertTrue(seenByA.rssi in VirtualAir.RSSI_MIN..VirtualAir.RSSI_MAX)
        }

    @Test
    fun aRadioThatStoppedScanningOrAdvertisingSeesAndShowsNothing() =
        runTest {
            val air = air()
            val a = air.radio()
            val b = air.radio()
            val c = air.radio()

            b.startAdvertising(PAYLOAD_B).ok()
            a.startScanning().ok()
            a.stopScanning()
            c.startAdvertising(PAYLOAD_A).ok()
            c.stopAdvertising()
            c.stopScanning()
            b.startScanning().ok()

            assertNull(withTimeoutOrNull(QUIET_MILLIS) { a.events.first() })
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { b.events.first() })
        }

    @Test
    fun streamOf2KbArrivesInOrderAtMtu23() = streamArrivesInOrder(mtu = 23)

    @Test
    fun streamOf2KbArrivesInOrderAtMtu247() = streamArrivesInOrder(mtu = 247)

    private fun streamArrivesInOrder(mtu: Int) =
        runTest {
            val air = air()
            val pair = linked(air, centralMtu = mtu, peripheralMtu = 517)
            val link = pair.link
            val inbound = pair.inbound
            val toPeripheral = ByteArray(2_048) { it.toByte() }
            val toCentral = toPeripheral.reversedArray()

            assertEquals(mtu, link.attMtu)
            assertEquals(mtu, inbound.attMtu)
            assertContentEquals(CAPABILITY, link.readCapability().ok())
            link.write(toPeripheral).ok()
            inbound.write(toCentral).ok()

            val chunks = (2_048 + mtu - RadioPort.ATT_HEADER - 1) / (mtu - RadioPort.ATT_HEADER)
            val atPeripheral = inbound.incoming.take(chunks).toList()
            val atCentral = link.incoming.take(chunks).toList()
            assertTrue((atPeripheral + atCentral).all { it.size <= mtu - RadioPort.ATT_HEADER })
            assertContentEquals(toPeripheral, atPeripheral.reduce(ByteArray::plus))
            assertContentEquals(toCentral, atCentral.reduce(ByteArray::plus))
        }

    @Test
    fun aDisconnectReachesBothSidesAfterTheBytesSentBeforeIt() =
        runTest {
            val air = air()
            val pair = linked(air)
            val (central, peripheral) = pair
            val link = pair.link
            val inbound = pair.inbound

            assertSame(peripheral.handle, link.peer)
            assertSame(central.handle, inbound.peer)
            link.write(byteArrayOf(1, 2, 3)).ok()
            link.disconnect()

            assertSame(link, central.events.next<RadioEvent.Disconnected>().link)
            assertSame(inbound, peripheral.events.next<RadioEvent.Disconnected>().link)
            assertContentEquals(byteArrayOf(1, 2, 3), inbound.incoming.toList().single())
            assertEquals(emptyList(), link.incoming.toList())
            assertEquals(RadioFailure.LINK_CLOSED, link.write(byteArrayOf(4)).failure())
            assertEquals(RadioFailure.LINK_CLOSED, inbound.write(byteArrayOf(4)).failure())
            assertEquals(RadioFailure.LINK_CLOSED, link.readCapability().failure())
            link.disconnect()
            inbound.disconnect()
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { central.events.first() })
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { peripheral.events.first() })
        }

    @Test
    fun simultaneousDisconnectsReportOncePerSide() =
        runTest {
            val air = air()
            val pair = linked(air)
            val (central, peripheral) = pair
            val link = pair.link
            val inbound = pair.inbound

            link.disconnect()
            inbound.disconnect()

            assertSame(link, central.events.next<RadioEvent.Disconnected>().link)
            assertSame(inbound, peripheral.events.next<RadioEvent.Disconnected>().link)
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { central.events.first() })
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { peripheral.events.first() })
        }

    @Test
    fun aPeripheralDisconnectReachesTheCentral() =
        runTest {
            val air = air()
            val pair = linked(air)
            val central = pair.central
            val link = pair.link
            val inbound = pair.inbound

            inbound.disconnect()

            assertSame(link, central.events.next<RadioEvent.Disconnected>().link)
            assertEquals(emptyList(), link.incoming.toList())
        }

    @Test
    fun invalidArgumentsAndUnreachablePeersFailAsValues() =
        runTest {
            val air = air()
            val a = air.radio()
            val b = air.radio()

            assertEquals(RadioFailure.INVALID_ARGUMENT, a.startAdvertising(ByteArray(15)).failure())
            assertEquals(
                RadioFailure.INVALID_ARGUMENT,
                a.serveCapability(ByteArray(RadioPort.CAPABILITY_RECORD_MAX + 1)).failure(),
            )
            assertEquals(RadioFailure.PEER_UNREACHABLE, a.connect(b.handle).failure())
            assertEquals(RadioFailure.PEER_UNREACHABLE, a.connect(air().radio().handle).failure())
            a.startAdvertising(PAYLOAD_A).ok()
            assertEquals(RadioFailure.PEER_UNREACHABLE, a.connect(a.handle).failure())
            b.startAdvertising(PAYLOAD_B).ok()
            assertEquals(
                RadioFailure.CAPABILITY_MISSING,
                a
                    .connect(b.handle)
                    .ok()
                    .readCapability()
                    .failure(),
            )
        }

    @Test
    fun theSameSeedYieldsTheSameEventLog() =
        runTest {
            assertEquals(hubScenario(seed = 1), hubScenario(seed = 1))
        }

    @Test
    fun anotherSeedChangesTheLatencyOrder() =
        runTest {
            val order = { log: List<String> -> log.map { it.substringAfter(' ') } }

            assertNotEquals(order(hubScenario(seed = 1)), order(hubScenario(seed = 2)))
        }

    // Two centrals each write three 1-byte chunks to one hub at the same instant; the hub logs
    // each arrival's time (relative to the scenario's start), sender and byte.
    private suspend fun TestScope.hubScenario(seed: Long): List<String> {
        val clock = TestClock(testScheduler)
        val air = VirtualAir(backgroundScope, clock, SeededRandom(seed))
        val hub = air.radio()
        hub.startAdvertising(PAYLOAD_B).ok()
        val links =
            listOf("A", "C").map { name ->
                val link = air.radio().connect(hub.handle).ok()
                Triple(name, link, hub.events.next<RadioEvent.Connected>().link)
            }
        val start = clock.monotonicMillis()
        val log = mutableListOf<String>()
        val collectors =
            links.map { (name, _, atHub) ->
                launch {
                    atHub.incoming.take(3).collect { log += "${clock.monotonicMillis() - start} $name${it.single()}" }
                }
            }
        links.forEach { (_, link, _) -> repeat(3) { link.write(byteArrayOf(it.toByte())).ok() } }
        collectors.joinAll()
        return log
    }

    private data class Linked(
        val central: VirtualRadio,
        val peripheral: VirtualRadio,
        val link: CentralLink,
        val inbound: RadioLink,
    )

    private suspend fun linked(
        air: VirtualAir,
        centralMtu: Int = VirtualAir.DEFAULT_ATT_MTU,
        peripheralMtu: Int = VirtualAir.DEFAULT_ATT_MTU,
    ): Linked {
        val central = air.radio(maxAttMtu = centralMtu)
        val peripheral = air.radio(maxAttMtu = peripheralMtu)
        peripheral.startAdvertising(PAYLOAD_B).ok()
        peripheral.serveCapability(CAPABILITY).ok()
        val link = central.connect(peripheral.handle).ok()
        return Linked(central, peripheral, link, peripheral.events.next<RadioEvent.Connected>().link)
    }

    private fun TestScope.air(): VirtualAir = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))

    private suspend inline fun <reified E : RadioEvent> Flow<RadioEvent>.next(): E = assertIs<E>(first())

    private fun <T> RadioResult<T>.ok(): T = assertIs<RadioResult.Ok<T>>(this).value

    private fun RadioResult<*>.failure(): RadioFailure = assertIs<RadioResult.Failed>(this).reason

    private companion object {
        val PAYLOAD_A = ByteArray(RadioPort.ADVERTISEMENT_LENGTH) { 0x20 }
        val PAYLOAD_B = ByteArray(RadioPort.ADVERTISEMENT_LENGTH) { 0x2F }
        val CAPABILITY = byteArrayOf(1, 1)
        const val QUIET_MILLIS = 60_000L
    }
}
