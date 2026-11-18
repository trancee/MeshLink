package ch.trancee.meshlink.internal

import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The iOS radio (ADR 0017): [AppleCentral] scans and connects as central, and
 * [ApplePeripheralHost] advertises and serves as peripheral, each fed by the CoreBluetooth
 * callbacks in [delegates].
 */
internal class AppleRadio(
    private val bluetooth: CoreBluetooth,
) : RadioPort {
    private val inbox = Channel<RadioEvent>(Channel.UNLIMITED)

    private val central = AppleCentral(bluetooth) { inbox.trySend(it) }

    private val host: ApplePeripheralHost =
        ApplePeripheralHost(bluetooth, onPowerLoss = { channels.powerLost() }) { inbox.trySend(it) }

    private val channels = AppleChannelListener(bluetooth, host.managerState) { inbox.trySend(it) }

    /** Where the thin layer passes CoreBluetooth's callbacks on. */
    val delegates = CoreBluetoothDelegates(central, central.links, host, channels)

    private var opened = false

    /**
     * What the radio misses, once both managers know their state. Collecting it creates the
     * managers, so iOS may show its Bluetooth prompt then (ADR 0007 decision 3).
     */
    val requirements: Flow<Set<Requirement>> =
        flow {
            if (!opened) {
                opened = true
                bluetooth.open(delegates)
            }
            emitAll(
                combine(central.managerState, host.managerState) { central, peripheral ->
                    val missing = listOf(central, peripheral).map { it?.let(::missing) }
                    if (null in missing) null else missing.filterNotNull().flatten().toSet()
                }.filterNotNull().distinctUntilChanged(),
            )
        }

    /** What a manager in [state] misses, or null while it cannot tell yet. */
    private fun missing(state: Long): Set<Requirement>? =
        when (state) {
            CbState.POWERED_ON -> emptySet()
            CbState.UNSUPPORTED -> setOf(Requirement.Unsupported)
            CbState.UNAUTHORIZED -> setOf(DENIED)
            CbState.POWERED_OFF, CbState.RESETTING -> setOf(Requirement.BluetoothOff)
            else -> if (bluetooth.authorization == CbAuthorization.NOT_DETERMINED) setOf(DENIED) else null
        }

    /** An iPhone holds at most 3 incoming links on every iOS version (ADR 0009 decision 6; library rule R16). */
    override val peripheralLinkCap: Int = PERIPHERAL_LINK_CAP

    /** Core Bluetooth neither reports nor selects the PHY, so an iPhone never claims 2M (ticket 32 Q9). */
    override val supportsLe2MPhy: Boolean = false

    override val events: Flow<RadioEvent> = inbox.receiveAsFlow()

    /**
     * Advertises the MESH service in its 32-bit form and [payload] as a 128-bit service UUID, after
     * stopping any earlier advertisement: CoreBluetooth refuses a second one with
     * `CBError.alreadyAdvertising`. iOS chooses the advertising parameters and moves service UUIDs
     * that don't fit into an overflow area while the app is in the background (ticket 32).
     */
    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> {
        if (payload.size != RadioPort.ADVERTISEMENT_LENGTH) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        bluetooth.stopAdvertising()
        bluetooth.advertise(listOf(AppleGatt.MESH_SHORT, payload.copyOf()))
        host.advertising = true
        return RadioResult.Ok(Unit)
    }

    override fun stopAdvertising() {
        host.advertising = false
        bluetooth.stopAdvertising()
    }

    override fun startScanning(): RadioResult<Unit> = central.startScanning()

    override fun stopScanning() = central.stopScanning()

    override fun serveCapability(record: ByteArray): RadioResult<Unit> = host.serve(record)

    override suspend fun listenForChannels(): RadioResult<Int> = channels.listen()

    override fun stopListening() = channels.stop()

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> = central.connect(peer)
}

private val DENIED = Requirement.PermissionDenied(PermissionKind.Bluetooth)

private const val PERIPHERAL_LINK_CAP = 3

/** The MeshLink GATT service and advertisement UUIDs as `CBUUID.data` (spec D-12 and D-13). */
internal object AppleGatt {
    /** The MESH service UUID `0x4D455348` in its 32-bit form, as it is advertised and scanned for. */
    val MESH_SHORT: ByteArray = "4d455348".hexToByteArray()

    /** The MESH service UUID in its 128-bit form, as CoreBluetooth may report it in an advertisement. */
    val MESH_FULL: ByteArray = "4d45534800001000800000805f9b34fb".hexToByteArray()

    /**
     * The served GATT service. It is off the Bluetooth Base UUID so no stack can shorten it: a vendor
     * stack that sends the MESH UUID in its 32-bit form, which ATT forbids, hides the service from every central.
     */
    val SERVICE: ByteArray = "4d4553484d4c4e4b8000000000000000".hexToByteArray()

    /** The capability characteristic: read, no encryption. */
    val CAPABILITY: ByteArray = "4d4553484d4c4e4b8001000000000001".hexToByteArray()

    /** The central-to-peripheral stream: write without response. */
    val TO_PERIPHERAL: ByteArray = "4d4553484d4c4e4b8001000000000002".hexToByteArray()

    /** The peripheral-to-central stream: notify. */
    val TO_CENTRAL: ByteArray = "4d4553484d4c4e4b8001000000000003".hexToByteArray()
}

/** A peer as iOS knows it: a remote [peripheral], equal by its identifier. */
internal class ApplePeer(
    val peripheral: CbPeripheral,
) : PeerHandle {
    override fun equals(other: Any?): Boolean = other is ApplePeer && other.peripheral.id == peripheral.id

    override fun hashCode(): Int = peripheral.id.hashCode()
}

/** A peer as iOS knows it when it connected to this peripheral: a remote central, by its identifier [id]. */
internal data class AppleCentralPeer(
    val id: String,
) : PeerHandle
