package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Android radio's L2CAP CoC half (ticket 31a): the listener as peripheral, channels a central
 * opens, and the byte stream over each. Blocking socket calls run on real I/O threads, so tests
 * wait for them in real time with [eventually].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    shadows = [
        ShadowMeshAdapter::class,
        ShadowMeshAdvertiser::class,
        ShadowMeshManager::class,
        ShadowMeshGattServer::class,
        ShadowMeshDevice::class,
        ShadowMeshGatt::class,
        ShadowMeshSocket::class,
        ShadowMeshServerSocket::class,
    ],
)
class AndroidRadioChannelTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val device = adapter.getRemoteDevice("00:11:22:33:44:55")
    private val peer = AndroidPeer(device)

    @Before
    fun arrangeAPeripheral() {
        resetRadioShadows()
        ShadowMeshDevice.discoverable += MeshGatt.service()
        ShadowMeshGatt.record = ByteArray(1)
    }

    @After
    fun endWaitingAccepts() = resetRadioShadows()

    @Test
    @Config(sdk = [26])
    fun belowAndroid10TheRadioNeitherListensNorOpensChannels() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)

            // Act
            val listening = radio.listenForChannels()
            radio.stopListening()
            val opened = link.openChannel(PSM)

            // Assert
            assertEquals(RadioFailure.UNSUPPORTED, assertIs<RadioResult.Failed>(listening).reason)
            assertEquals(RadioFailure.UNSUPPORTED, assertIs<RadioResult.Failed>(opened).reason)
            assertEquals(0, ShadowMeshAdapter.listens.get())
            assertTrue(ShadowMeshDevice.channels.isEmpty())
        }

    @Test
    fun listeningOpensOneInsecureServerAndReturnsItsPsm() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val first = radio.listenForChannels()
            val again = radio.listenForChannels()

            // Assert
            assertEquals(ShadowMeshServerSocket.FIRST_PSM, assertIs<RadioResult.Ok<Int>>(first).value)
            assertEquals(ShadowMeshServerSocket.FIRST_PSM, assertIs<RadioResult.Ok<Int>>(again).value)
            assertEquals(1, ShadowMeshAdapter.listens.get())
        }

    @Test
    fun aServerTheStackRefusesFailsWithRadioError() =
        runTest {
            // Arrange
            ShadowMeshAdapter.refuseListen = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.listenForChannels()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun anAcceptedChannelOpensWithThePeerOfThatDevicesGattLink() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.listenForChannels()

            // Act
            val accepted = server().connect(device)
            val opened = eventually { radio.events.first() }

            // Assert
            val channel = assertIs<RadioEvent.ChannelOpened>(opened).channel
            assertEquals(peer, channel.peer)
            Shadow.extract<ShadowMeshSocket>(accepted).inputStreamFeeder.write(byteArrayOf(7, 8))
            assertContentEquals(byteArrayOf(7, 8), eventually { channel.incoming.first() })
        }

    @Test
    fun stoppingClosesTheServerButNotItsChannelsAndListeningAgainGetsANewPsm() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.listenForChannels()
            val first = server()
            val accepted = Shadow.extract<ShadowMeshSocket>(first.connect(device))
            eventually { radio.events.first() }

            // Act
            radio.stopListening()
            radio.stopListening()
            val again = radio.listenForChannels()

            // Assert
            assertEquals(1, first.closes)
            assertEquals(0, accepted.closes.get())
            assertEquals(ShadowMeshServerSocket.FIRST_PSM + 1, assertIs<RadioResult.Ok<Int>>(again).value)
        }

    @Test
    fun cancellingTheRadiosScopeClosesTheServerAndItsChannels() =
        runTest {
            // Arrange
            val scope = CoroutineScope(Job())
            val radio = AndroidRadio(context, manager, adapter, scope)
            radio.listenForChannels()
            val accepted = Shadow.extract<ShadowMeshSocket>(server().connect(device))
            eventually { radio.events.first() }

            // Act
            scope.cancel()

            // Assert
            eventually { while (accepted.closes.get() == 0) yield() }
            assertEquals(1, server().closes)
        }

    @Test
    fun anAcceptThatFailsClosesTheServerAndEveryChannelItAccepted() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.listenForChannels()
            val first = server()
            val accepted = List(2) { Shadow.extract<ShadowMeshSocket>(first.connect(device)) }
            eventually { radio.events.take(2).toList() }

            // Act
            first.fail()
            eventually { while (first.closes == 0 || accepted.any { it.closes.get() == 0 }) yield() }
            val again = radio.listenForChannels()

            // Assert
            assertEquals(listOf(1, 1), accepted.map { it.closes.get() })
            assertEquals(ShadowMeshServerSocket.FIRST_PSM + 1, assertIs<RadioResult.Ok<Int>>(again).value)
        }

    @Test
    fun anAcceptThatFailsBeforeAnyChannelClosesTheServer() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.listenForChannels()
            val first = server()

            // Act
            first.fail()
            eventually { while (first.closes == 0) yield() }
            val again = radio.listenForChannels()

            // Assert
            assertEquals(1, first.closes)
            assertEquals(ShadowMeshServerSocket.FIRST_PSM + 1, assertIs<RadioResult.Ok<Int>>(again).value)
        }

    @Test
    fun bluetoothOffClosesTheServerAndItsChannels() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.listenForChannels()
            val first = server()
            val accepted = Shadow.extract<ShadowMeshSocket>(first.connect(device))
            val channel = assertIs<RadioEvent.ChannelOpened>(eventually { radio.events.first() }).channel

            // Act
            radio.bluetoothOff()

            // Assert
            assertEquals(1, first.closes)
            assertEquals(1, accepted.closes.get())
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(channel.write(byteArrayOf(1))).reason)
        }

    @Test
    fun openingConnectsAnInsecureChannelToThePsmOnTheLinksDevice() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)

            // Act
            val result = link.openChannel(PSM)

            // Assert
            val channel = assertIs<RadioResult.Ok<RadioStream>>(result).value
            val opened = ShadowMeshDevice.channels.single()
            assertEquals(PSM, opened.psm)
            assertEquals(peer, channel.peer)
            assertTrue(opened.socket.isConnected)
        }

    @Test
    fun aChannelOnAClosedLinkFailsAsClosed() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            link.disconnect()

            // Act
            val result = link.openChannel(PSM)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(ShadowMeshDevice.channels.isEmpty())
        }

    @Test
    fun aSocketTheStackRefusesFailsWithRadioError() =
        runTest {
            // Arrange
            ShadowMeshDevice.refuseChannel = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)

            // Act
            val result = link.openChannel(PSM)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun aConnectThatFailsFailsWithRadioErrorAndClosesTheSocket() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            ShadowMeshSocket.refuseConnect = true
            val link = connected(radio)

            // Act
            val result = link.openChannel(PSM)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
            assertEquals(
                1,
                ShadowMeshDevice.channels
                    .single()
                    .shadow.closes
                    .get(),
            )
        }

    @Test
    fun aLinkThatClosesWhileItsChannelConnectsFailsAsClosed() =
        runTest {
            // Arrange
            ShadowMeshSocket.blockConnect = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            val opening = async(Dispatchers.Default) { link.openChannel(PSM) }
            val socket = eventually { blockedSocket() }

            // Act
            remoteDisconnect()

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(opening.await()).reason)
            assertEquals(1, socket.closes.get())
        }

    @Test
    fun aLinkThatClosesAsTheSocketIsCreatedFailsAsClosedAndClosesTheSocket() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            ShadowMeshDevice.onChannel = ::remoteDisconnect

            // Act
            val result = link.openChannel(PSM)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertEquals(
                1,
                ShadowMeshDevice.channels
                    .single()
                    .shadow.closes
                    .get(),
            )
        }

    @Test
    fun cancellingTheOpenClosesTheSocket() =
        runTest {
            // Arrange
            ShadowMeshSocket.blockConnect = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            val opening = async(Dispatchers.Default) { link.openChannel(PSM) }
            val socket = eventually { blockedSocket() }

            // Act
            opening.cancel()
            eventually { opening.join() }

            // Assert
            assertEquals(1, socket.closes.get())
        }

    @Test
    fun closingTheLinkClosesItsChannel() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            val channel = opened(link)

            // Act
            link.disconnect()

            // Assert
            assertEquals(
                1,
                ShadowMeshDevice.channels
                    .single()
                    .shadow.closes
                    .get(),
            )
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(channel.write(byteArrayOf(1))).reason)
        }

    @Test
    fun readsArriveInOrderAndEofEndsTheStreamAndClosesTheSocket() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val shadow = ShadowMeshDevice.channels.single().shadow
            val received = async(Dispatchers.Default) { channel.incoming.toList() }

            // Act
            shadow.inputStreamFeeder.write(byteArrayOf(1, 2, 3))
            shadow.inputStreamFeeder.flush()
            shadow.inputStreamFeeder.close()

            // Assert
            val bytes = eventually { received.await() }.flatMap { it.toList() }
            assertEquals(listOf<Byte>(1, 2, 3), bytes)
            assertEquals(1, shadow.closes.get())
        }

    @Test
    fun aCollectorThatStopsWhileAReadBlocksClosesTheChannel() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val shadow = ShadowMeshDevice.channels.single().shadow
            val pipe = shadow.inputStream
            val reading = CompletableDeferred<Unit>()
            shadow.input =
                object : InputStream() {
                    override fun read(): Int = pipe.read()

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        reading.complete(Unit)
                        return pipe.read(b, off, len)
                    }
                }
            val collecting = launch(Dispatchers.Default) { channel.incoming.collect {} }
            eventually { reading.await() }

            // Act
            eventually { collecting.cancelAndJoin() }

            // Assert
            assertEquals(1, shadow.closes.get())
        }

    @Test
    fun aReadThatFailsEndsTheStream() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val shadow = ShadowMeshDevice.channels.single().shadow
            shadow.input =
                object : InputStream() {
                    override fun read(): Int = throw IOException("reset")

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int = throw IOException("reset")
                }

            // Act
            val received = eventually { channel.incoming.toList() }

            // Assert
            assertTrue(received.isEmpty())
            assertEquals(1, shadow.closes.get())
        }

    @Test
    fun writesArriveWholeAndInOrder() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val sink =
                ShadowMeshDevice.channels
                    .single()
                    .shadow.outputStreamSink

            // Act
            val first = channel.write(byteArrayOf(1, 2))
            val second = channel.write(byteArrayOf(3))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(first)
            assertIs<RadioResult.Ok<Unit>>(second)
            val bytes = ByteArray(3)
            eventually { withContext(Dispatchers.IO) { sink.readNBytes(bytes, 0, bytes.size) } }
            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        }

    @Test
    fun aWriteThatFailsClosesTheChannel() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val shadow = ShadowMeshDevice.channels.single().shadow
            shadow.outputStreamSink.close()

            // Act
            val result = channel.write(byteArrayOf(1))

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertEquals(1, shadow.closes.get())
        }

    @Test
    fun disconnectingClosesTheSocketOnceAndFailsLaterWrites() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val channel = opened(connected(radio))
            val shadow = ShadowMeshDevice.channels.single().shadow

            // Act
            channel.disconnect()
            channel.disconnect()
            val result = channel.write(byteArrayOf(1))

            // Assert
            assertEquals(1, shadow.closes.get())
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(eventually { channel.incoming.toList() }.isEmpty())
        }

    @Test
    fun aClosedChannelLeavesItsLinkOpenAndIsNotClosedAgainWithIt() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)
            val channel = opened(link)

            // Act
            channel.disconnect()
            val written = link.write(byteArrayOf(1))
            link.disconnect()

            // Assert
            assertIs<RadioResult.Ok<Unit>>(written)
            assertEquals(
                1,
                ShadowMeshDevice.channels
                    .single()
                    .shadow.closes
                    .get(),
            )
        }

    private suspend fun connected(radio: AndroidRadio): CentralLink {
        val result = radio.connect(peer)
        return assertIs<RadioResult.Ok<CentralLink>>(result).value
    }

    private suspend fun opened(link: CentralLink): RadioStream {
        val result = link.openChannel(PSM)
        return assertIs<RadioResult.Ok<RadioStream>>(result).value
    }

    private fun server(): ShadowMeshServerSocket = ShadowMeshServerSocket.servers.last()

    /** The socket of the channel being opened; its connect blocks until the socket closes. */
    private suspend fun blockedSocket(): ShadowMeshSocket {
        while (ShadowMeshDevice.channels.isEmpty()) yield()
        return ShadowMeshDevice.channels.single().shadow
    }

    private fun remoteDisconnect() {
        val gatt = ShadowMeshDevice.gatts.last()
        val shadow = Shadow.extract<ShadowMeshGatt>(gatt)
        shadow.gattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
    }

    /** Runs [block] in real time, as the socket threads it waits for do. */
    private suspend fun <T> eventually(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Default) { withTimeout(WAIT_MILLIS) { block() } }

    private companion object {
        const val PSM = 0x0081
        const val WAIT_MILLIS = 5_000L
    }
}
