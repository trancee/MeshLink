package bakeoff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** One central attempt: connect, identify, hello over GATT, then each configured bearer, then disconnect. */
class CentralAttempt(
    private val config: BakeoffConfig,
    private val driver: CentralDriver,
    private val recorder: Recorder,
    private val identity: PeerIdentity,
    private val excluded: MutableSet<String>,
    private val attempt: Int,
) {
    private val local = recorder.local
    private val testTimeoutMs = 120_000L

    suspend fun run(): Boolean {
        val clock = PhaseClock()
        var connection: CentralConnection? = null
        var peerInfo: DeviceInfo? = null
        try {
            connection = withTimeout(config.scanTimeoutSeconds * 1000L + 30_000) {
                driver.connect(ConnectRequest(config.phy, excluded, config.scanTimeoutSeconds * 1000L), clock)
            }
            val peer = withTimeout(10_000) { PeerIdentity.decodeControl(connection.readControl()) }
            clock.mark("control_read")
            if (peer.runTag != config.runTag) {
                excluded += connection.address
                recorder.record("foreign_peer", "skipped", local, DeviceInfo.decode(peer.info), attempt = attempt, extra = mapOf("address" to connection.address))
                return false
            }
            peerInfo = DeviceInfo.decode(peer.info)
            val gattPipe = MessagePipe(connection.gattLink)
            withTimeout(10_000) {
                gattPipe.send(MessageType.HELLO, identity.encodeHello())
                gattPipe.expect(MessageType.HELLO)
            }
            clock.mark("first_app_byte")
            val phy = connection.phyReport()
            recorder.record(
                "gatt_connect", "ok", local, peerInfo, bearer = Bearer.GATT.label, phy = phy, mtu = connection.mtu,
                attempt = attempt, timings = clock.marks, extra = mapOf("peer_node_id" to peer.nodeIdHex),
            )
            var allOk = true
            for (bearer in config.bearers) {
                allOk = runBearer(bearer, connection, gattPipe, peerInfo) && allOk
            }
            withTimeout(5_000) { gattPipe.send(MessageType.BYE) }
            return allOk
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            recordConnectFailure(clock, connection, peerInfo, "timeout after ${clock.marks.keys.lastOrNull() ?: "start"}")
            return false
        } catch (failure: Throwable) {
            recordConnectFailure(clock, connection, peerInfo, failure.describe())
            return false
        } finally {
            connection?.let { runCatching { it.disconnect() } }
        }
    }

    private fun recordConnectFailure(clock: PhaseClock, connection: CentralConnection?, peerInfo: DeviceInfo?, error: String) {
        recorder.record(
            "gatt_connect", "fail", local, peerInfo, bearer = Bearer.GATT.label, phy = connection?.phyReport(),
            mtu = connection?.mtu, attempt = attempt, timings = clock.marks, error = error,
        )
    }

    private suspend fun runBearer(bearer: Bearer, connection: CentralConnection, gattPipe: MessagePipe, peerInfo: DeviceInfo): Boolean {
        val phy = connection.phyReport()
        if (bearer == Bearer.GATT) {
            if (config.plan != Plan.SUITE) return true
            return runSuite(gattPipe, bearer, phy, connection.mtu, peerInfo)
        }
        val clock = PhaseClock()
        var link: ByteLink? = null
        try {
            val psm = withTimeout(10_000) { connection.readPsm() }
            clock.mark("psm_read")
            link = withTimeout(30_000) { connection.openL2cap(psm, bearer == Bearer.L2CAP_SECURE) }
            clock.mark("l2cap_open")
            val pipe = MessagePipe(link)
            withTimeout(10_000) {
                pipe.send(MessageType.HELLO, identity.encodeHello())
                pipe.expect(MessageType.HELLO)
            }
            clock.mark("first_app_byte")
            recorder.record(
                "l2cap_connect", "ok", local, peerInfo, bearer = bearer.label, phy = phy, mtu = connection.mtu,
                attempt = attempt, timings = clock.marks, extra = mapOf("psm" to psm),
            )
            val ok = if (config.plan == Plan.SUITE) runSuite(pipe, bearer, phy, connection.mtu, peerInfo) else true
            runCatching { withTimeout(5_000) { pipe.send(MessageType.BYE) } }
            return ok
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            recorder.record("l2cap_connect", "fail", local, peerInfo, bearer = bearer.label, phy = phy, mtu = connection.mtu, attempt = attempt, timings = clock.marks, error = "timeout after ${clock.marks.keys.lastOrNull() ?: "start"}")
            return false
        } catch (failure: Throwable) {
            recorder.record("l2cap_connect", "fail", local, peerInfo, bearer = bearer.label, phy = phy, mtu = connection.mtu, attempt = attempt, timings = clock.marks, error = failure.describe())
            return false
        } finally {
            link?.close("attempt finished")
        }
    }

    private suspend fun runSuite(pipe: MessagePipe, bearer: Bearer, phy: String, mtu: Int, peerInfo: DeviceInfo): Boolean {
        val context = TestContext(recorder, local, peerInfo, bearer, phy, mtu, attempt)
        return try {
            withTimeout(testTimeoutMs) { CentralSuite(pipe, context).runAll(config.throughputBytes, config.echoCount, config.repetitions) }
            true
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            recorder.record("suite", "fail", local, peerInfo, bearer = bearer.label, phy = phy, mtu = mtu, attempt = attempt, error = "suite timeout")
            false
        } catch (failure: Throwable) {
            recorder.record("suite", "fail", local, peerInfo, bearer = bearer.label, phy = phy, mtu = mtu, attempt = attempt, error = failure.describe())
            false
        }
    }
}
