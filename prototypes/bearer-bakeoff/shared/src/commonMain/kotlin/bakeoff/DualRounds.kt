package bakeoff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Crossed-connection probe: every node advertises and, at each wall-clock-aligned round start, dials the first
 * peer it finds. Records whether two links to the same peer existed and how the keep-one rule resolved them.
 */
class DualRounds(
    private val config: BakeoffConfig,
    private val driver: CentralDriver,
    private val recorder: Recorder,
    private val identity: PeerIdentity,
    private val registry: LinkRegistry,
    private val status: (String) -> Unit,
) {
    @OptIn(ExperimentalTime::class)
    suspend fun run() {
        val periodMs = config.roundSeconds * 1000L
        val excluded = mutableSetOf<String>()
        repeat(config.attempts) { index ->
            val now = Clock.System.now().toEpochMilliseconds()
            val roundStart = (now / periodMs + 1) * periodMs
            delay(roundStart - now + Random.nextLong(0, 200))
            status("dual round ${index + 1}/${config.attempts}")
            runRound(index + 1, roundStart / periodMs, periodMs, excluded)
        }
    }

    private suspend fun runRound(attempt: Int, round: Long, periodMs: Long, excluded: MutableSet<String>) {
        registry.resetRound()
        val clock = PhaseClock()
        var connection: CentralConnection? = null
        var peer: PeerIdentity? = null
        var centralError: String? = null
        try {
            connection = withTimeout(periodMs / 2) {
                driver.connect(ConnectRequest(config.phy, excluded, periodMs / 2), clock)
            }
            val control = PeerIdentity.decodeControl(withTimeout(5_000) { connection.readControl() })
            if (control.runTag != config.runTag) {
                excluded += connection.address
                throw ProtocolException("foreign peer")
            }
            val pipe = MessagePipe(connection.gattLink)
            withTimeout(5_000) {
                pipe.send(MessageType.HELLO, identity.encodeHello())
                pipe.expect(MessageType.HELLO)
            }
            clock.mark("first_app_byte")
            peer = control
            registry.onCentralLink(control.nodeId, connection)
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            centralError = "timeout after ${clock.marks.keys.lastOrNull() ?: "start"}"
        } catch (failure: Throwable) {
            centralError = failure.describe()
        }
        var crossedAtMs: Double? = null
        var action = "none"
        var crossedPeer: Long? = null
        while (clock.elapsedMs() < periodMs - 2_000) {
            if (crossedAtMs == null) {
                val crossed = registry.peers.entries.firstOrNull { it.value.central != null && it.value.peripheral != null }
                if (crossed != null) {
                    crossedAtMs = clock.elapsedMs()
                    crossedPeer = crossed.key
                    if (registry.keepsOwnCentral(crossed.key)) {
                        action = "kept_own_central"
                    } else {
                        action = "dropped_own_central"
                        crossed.value.central?.disconnect()
                        crossed.value.central = null
                    }
                }
            }
            delay(100)
        }
        val peerLinks = (crossedPeer ?: peer?.nodeId)?.let { registry.peers[it] }
        val peripheralSeen = registry.peers.values.any { it.peripheralSeen }
        recorder.record(
            "crossed_round", if (centralError == null || peripheralSeen) "ok" else "fail", recorder.local,
            peer?.let { DeviceInfo.decode(it.info) }, bearer = "gatt", phy = connection?.phyReport(), mtu = connection?.mtu,
            attempt = attempt, timings = clock.marks, error = centralError,
            extra = mapOf(
                "round" to round,
                "local_node_id" to identity.nodeIdHex,
                "peer_node_id" to (crossedPeer ?: peer?.nodeId)?.toULong()?.toString(16),
                "central_link" to (centralError == null),
                "peripheral_link_seen" to peripheralSeen,
                "crossed" to (crossedAtMs != null),
                "crossed_detected_ms" to crossedAtMs?.let(::round3),
                "keep_one_action" to action,
                "own_central_alive_at_end" to (peerLinks?.central?.isConnected ?: false),
                "peripheral_link_alive_at_end" to (peerLinks?.peripheral != null),
            ),
        )
        connection?.let { runCatching { it.disconnect() } }
    }
}
