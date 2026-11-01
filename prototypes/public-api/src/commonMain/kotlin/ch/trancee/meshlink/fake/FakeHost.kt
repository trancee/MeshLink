package ch.trancee.meshlink.fake

import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshLinkPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * A simulated phone in a [FakeMesh], used as the [MeshLinkPlatform] of one node. Radio and
 * permission toggles take effect immediately and make a started node blocked or running.
 * Test support for the prototype only.
 */
public class FakeHost internal constructor(private val mesh: FakeMesh, public val name: String) : MeshLinkPlatform() {
    /** Installation identity of the node on this host, derived from [name]. */
    public val nodeId: NodeId = NodeId.fromBytes(fnv1a64(name))

    @Volatile
    internal var engine: FakeNode? = null
        private set

    private var bluetoothOn = true
    private var supported = true
    private var radioInUse = false
    private val deniedPermissions = mutableSetOf<PermissionKind>()

    internal var present: Boolean = true
        private set

    /** Switches the Bluetooth radio on or off. */
    @Throws(CancellationException::class)
    public suspend fun setBluetoothOn(on: Boolean) {
        change { bluetoothOn = on }
    }

    /** Grants or revokes a permission. */
    @Throws(CancellationException::class)
    public suspend fun setPermissionGranted(kind: PermissionKind, granted: Boolean) {
        change { if (granted) deniedPermissions.remove(kind) else deniedPermissions.add(kind) }
    }

    /** Simulates a platform without Bluetooth Low Energy, such as the iOS simulator. */
    @Throws(CancellationException::class)
    public suspend fun setSupported(isSupported: Boolean) {
        change { supported = isSupported }
    }

    /** Simulates another component holding the radio. */
    @Throws(CancellationException::class)
    public suspend fun setRadioInUse(inUse: Boolean) {
        change { radioInUse = inUse }
    }

    /** Moves the host into or out of range of every other host, keeping its links. */
    @Throws(CancellationException::class)
    public suspend fun setPresent(isPresent: Boolean) {
        change { present = isPresent }
    }

    internal fun missingRequirements(): Set<Requirement> = buildSet {
        if (!supported) add(Requirement.Unsupported)
        if (!bluetoothOn) add(Requirement.BluetoothOff)
        if (radioInUse) add(Requirement.RadioInUse)
        deniedPermissions.forEach { add(Requirement.PermissionDenied(it)) }
    }

    override fun createEngine(config: MeshLinkConfig): FakeNode = FakeNode(mesh, this, config).also { engine = it }

    private suspend fun change(block: () -> Unit) {
        mesh.onMedium {
            block()
            engine?.takeIf { !it.isClosed }?.reevaluateRequirements()
            mesh.topologyChanged()
        }
    }

    private fun fnv1a64(text: String): ByteArray {
        var hash = FNV_OFFSET
        text.encodeToByteArray().forEach { hash = (hash xor (it.toLong() and 0xff)) * FNV_PRIME }
        return ByteArray(NodeId.SIZE_BYTES) { (hash ushr (it * Byte.SIZE_BITS)).toByte() }
    }

    private companion object {
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}
