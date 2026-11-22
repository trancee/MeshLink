package ch.trancee.meshlink.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    fun eachAdvertisementGetsAFreshAddressAndIsLogged() =
        runTest {
            val air = air()
            val a = air.radio()
            val b = air.radio()
            a.startScanning().ok()

            b.startAdvertising(PAYLOAD_B).ok()
            val first =
                a.events
                    .next<RadioEvent.Sighted>()
                    .sighting.peer
            val link = a.connect(first).ok()
            b.startAdvertising(PAYLOAD_A).ok()
            val second =
                a.events
                    .next<RadioEvent.Sighted>()
                    .sighting.peer

            assertNotEquals(first, second)
            assertSame(second, b.handle)
            assertSame(first, link.peer)
            assertEquals(RadioFailure.PEER_UNREACHABLE, a.connect(first).failure())
            a.connect(second).ok()
            assertEquals(2, b.advertisements.size)
            assertContentEquals(PAYLOAD_A, b.advertisements.last().second)
            assertTrue(b.advertisements.first().first < b.advertisements.last().first)
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
    fun aCentralOpensAChannelToAListeningPeripheralAndBothStreamWholeWrites() =
        runTest {
            val pair = linked(air(), coc = true)
            val peripheral = pair.peripheral
            val link = pair.link
            val inbound = pair.inbound
            val psm = peripheral.listenForChannels().ok()

            val channel = link.openChannel(psm).ok()
            val accepted = peripheral.events.next<RadioEvent.ChannelOpened>().channel
            val big = ByteArray(VirtualAir.DEFAULT_ATT_MTU * 2) { it.toByte() }
            channel.write(big).ok()
            channel.write(PAYLOAD_A).ok()
            accepted.write(PAYLOAD_B).ok()

            assertEquals(psm, peripheral.listenForChannels().ok())
            assertSame(inbound.peer, accepted.peer)
            assertSame(peripheral.handle, channel.peer)
            assertEquals(
                listOf(big.toList(), PAYLOAD_A.toList()),
                accepted.incoming
                    .take(2)
                    .toList()
                    .map { it.toList() },
            )
            assertContentEquals(PAYLOAD_B, channel.incoming.first())
            assertContentEquals(big + PAYLOAD_A, (channel as VirtualChannelEnd).written)
            assertSame(channel, (link as VirtualLinkEnd).channels.single())
            assertSame(accepted, (inbound as VirtualLinkEnd).channels.single())
        }

    @Test
    fun anUnrecordedAirKeepsNoCopiesOfWritesButTapsEachWithItsWriter() =
        runTest {
            // Arrange
            val air = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1), recording = false)
            val tapped = mutableListOf<Pair<VirtualRadio, List<Byte>>>()
            air.onWrite = { radio, bytes -> tapped += radio to bytes.toList() }
            val pair = linked(air, coc = true)
            val channel = pair.link.openChannel(pair.peripheral.listenForChannels().ok()).ok()

            // Act
            pair.link.write(PAYLOAD_A).ok()
            channel.write(PAYLOAD_B).ok()

            // Assert
            val link = pair.link as VirtualLinkEnd
            assertEquals(listOf(pair.central to PAYLOAD_A.toList(), pair.central to PAYLOAD_B.toList()), tapped)
            assertEquals(0, link.written.size)
            assertEquals(emptyList(), link.writes)
            assertEquals(0, (channel as VirtualChannelEnd).written.size)
        }

    @Test
    fun aChannelOpensOnlyToAListeningPeripheralAtItsPsm() =
        runTest {
            val air = air()
            val plain = linked(air)
            val coc = linked(air, coc = true)
            val peripheral = coc.peripheral
            val link = coc.link

            assertEquals(RadioFailure.UNSUPPORTED, plain.peripheral.listenForChannels().failure())
            assertEquals(RadioFailure.UNSUPPORTED, plain.link.openChannel(VirtualRadio.PSM).failure())
            assertEquals(RadioFailure.RADIO_ERROR, link.openChannel(VirtualRadio.PSM).failure())
            val psm = peripheral.listenForChannels().ok()
            assertEquals(RadioFailure.RADIO_ERROR, link.openChannel(psm + 1).failure())
            peripheral.refuseChannels = true
            assertEquals(RadioFailure.RADIO_ERROR, link.openChannel(psm).failure())
            peripheral.refuseChannels = false
            peripheral.stopListening()
            peripheral.stopListening()
            assertEquals(RadioFailure.RADIO_ERROR, link.openChannel(psm).failure())
            peripheral.listenForChannels().ok()
            link.disconnect()
            assertEquals(RadioFailure.LINK_CLOSED, link.openChannel(psm).failure())
        }

    @Test
    fun aChannelClosesWithItsLinkOrOnItsOwn() =
        runTest {
            val air = air()
            val first = linked(air, coc = true)
            val psm = first.peripheral.listenForChannels().ok()
            val channel = first.link.openChannel(psm).ok()
            val accepted =
                first.peripheral.events
                    .next<RadioEvent.ChannelOpened>()
                    .channel

            channel.disconnect()
            channel.disconnect()

            assertEquals(RadioFailure.LINK_CLOSED, channel.write(PAYLOAD_A).failure())
            assertEquals(emptyList(), accepted.incoming.toList())
            assertEquals(emptyList(), channel.incoming.toList())
            assertTrue(first.inbound.let { it as VirtualLinkEnd }.open)

            val second = linked(air, coc = true)
            val dropped = second.link.openChannel(second.peripheral.listenForChannels().ok()).ok()
            val other =
                second.peripheral.events
                    .next<RadioEvent.ChannelOpened>()
                    .channel
            (dropped as VirtualChannelEnd).drop()
            assertEquals(emptyList(), other.incoming.toList())

            val third = linked(air, coc = true)
            val lost = third.link.openChannel(third.peripheral.listenForChannels().ok()).ok()
            val lostAccepted =
                third.peripheral.events
                    .next<RadioEvent.ChannelOpened>()
                    .channel
            (third.inbound as VirtualLinkEnd).drop()
            assertEquals(emptyList(), lost.incoming.toList())
            assertEquals(emptyList(), lostAccepted.incoming.toList())
        }

    @Test
    fun aCentralLinkKeepsTheHandleItDialedWhenThePeripheralRotatesItsAddressMeanwhile() =
        runTest {
            // Arrange
            val air = air()
            val central = air.radio()
            val peripheral = air.radio()
            peripheral.startAdvertising(PAYLOAD_A).ok()
            val dialed = peripheral.handle

            // Act
            val connecting = async { central.connect(dialed).ok() }
            testScheduler.runCurrent()
            peripheral.startAdvertising(PAYLOAD_B).ok()
            val link = connecting.await()

            // Assert
            assertNotEquals(dialed, peripheral.handle)
            assertSame(dialed, link.peer)
        }

    @Test
    fun aPairsSetSignalStrengthReachesSightingsAndLinkReadsBothWays() =
        runTest {
            val air = air()
            val (central, peripheral, link) = linked(air)
            air.setRssi(central, peripheral, -60)
            central.startScanning().ok()
            peripheral.startScanning().ok()
            central.startAdvertising(PAYLOAD_A).ok()

            assertEquals(
                -60,
                central.events
                    .next<RadioEvent.Sighted>()
                    .sighting.rssi,
            )
            assertEquals(
                -60,
                peripheral.events
                    .next<RadioEvent.Sighted>()
                    .sighting.rssi,
            )
            assertEquals(-60, link.readRssi().ok())
            air.setRssi(peripheral, central, -80)
            assertEquals(-80, link.readRssi().ok())
            link.disconnect()
            assertEquals(RadioFailure.LINK_CLOSED, link.readRssi().failure())
        }

    @Test
    fun radiosOutOfRangeNeitherSightNorReachEachOtherAndMovingApartDropsTheirLink() =
        runTest {
            // Arrange
            val air = air()
            val near = air.radio()
            val far = air.radio()
            val scanner = air.radio()
            air.move(far, VirtualAir.DEFAULT_RANGE + 1, 0.0)
            near.startAdvertising(PAYLOAD_A).ok()
            far.startAdvertising(PAYLOAD_B).ok()
            val link = scanner.connect(near.handle).ok()
            val accepted = near.events.next<RadioEvent.Connected>().link

            // Act
            scanner.startScanning().ok()
            val sighted =
                scanner.events
                    .next<RadioEvent.Sighted>()
                    .sighting.peer
            val unreachable = scanner.connect(far.handle).failure()
            air.move(near, -VirtualAir.DEFAULT_RANGE - 1, 0.0)
            val dropped = listOf(scanner, near).map { it.events.next<RadioEvent.Disconnected>() }
            air.move(far, VirtualAir.DEFAULT_RANGE, 0.0)
            far.startAdvertising(PAYLOAD_B).ok()
            val inRange =
                scanner.events
                    .next<RadioEvent.Sighted>()
                    .sighting.peer

            // Assert
            assertSame(near.handle, sighted)
            assertEquals(RadioFailure.PEER_UNREACHABLE, unreachable)
            assertEquals(listOf(link, accepted), dropped.map { it.link })
            assertTrue(dropped.all { it.transient })
            assertSame(far.handle, inRange)
            assertNull(withTimeoutOrNull(QUIET_MILLIS) { scanner.events.first() })
        }

    @Test
    fun aBackgroundRadioIsSightedWithoutItsPayloadAndKeepsItsAddress() =
        runTest {
            val air = air()
            val scanner = air.radio()
            val background = air.radio()
            background.background = true
            background.startAdvertising(PAYLOAD_B).ok()
            val address = background.handle

            scanner.startScanning().ok()
            background.stopAdvertising()
            background.startAdvertising(PAYLOAD_A).ok()

            val sighting = scanner.events.next<RadioEvent.Sighted>().sighting
            assertNull(sighting.payload)
            assertSame(address, sighting.peer)
            assertSame(address, background.handle)
        }

    @Test
    fun aRadioSupportsTheLe2MPhyOnlyWhenAddedWithIt() =
        runTest {
            val air = air()

            assertTrue(air.radio(le2MPhy = true).supportsLe2MPhy)
            assertEquals(false, air.radio().supportsLe2MPhy)
        }

    @Test
    fun aRadioLogsWhenItStartsAndStopsScanning() =
        runTest {
            val clock = TestClock(testScheduler)
            val air = VirtualAir(backgroundScope, clock, SeededRandom(1))
            val radio = air.radio()
            val start = clock.monotonicMillis()

            radio.startScanning().ok()
            delay(4_000)
            radio.stopScanning()
            radio.stopScanning()

            assertEquals(listOf(start to true, start + 4_000 to false), radio.scanLog)
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
        coc: Boolean = false,
    ): Linked {
        val central = air.radio(maxAttMtu = centralMtu, coc = coc)
        val peripheral = air.radio(maxAttMtu = peripheralMtu, coc = coc)
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
