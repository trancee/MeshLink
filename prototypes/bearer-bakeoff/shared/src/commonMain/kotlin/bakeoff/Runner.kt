package bakeoff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

class BakeoffRunner(
    private val config: BakeoffConfig,
    private val local: DeviceInfo,
    sink: ResultSink,
    private val centralDriver: CentralDriver,
    private val peripheralDriver: PeripheralDriver,
    private val status: (String) -> Unit,
) {
    private val recorder = Recorder(config, local, sink)
    val identity: PeerIdentity = PeerIdentity(Random.nextLong(), config.runTag, local.encoded)
    private val registry = LinkRegistry(identity.nodeId)
    private var activePeripheralLinks = 0

    fun start(scope: CoroutineScope): Job = scope.launch {
        recorder.log("start role=${config.role} node=${identity.nodeIdHex} run=${config.runId}")
        try {
            when (config.role) {
                Role.PERIPHERAL -> servePeripheral()
                Role.CENTRAL -> runCentralAttempts()
                Role.HOLD -> runHold()
                Role.HUB_CENTRAL -> HubCentral(config, centralDriver, recorder, identity, status).run()
                Role.DUAL -> runDual(scope)
            }
            recorder.record("done", "ok", null, null, extra = mapOf("node_id" to identity.nodeIdHex))
            status("done")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            recorder.record("done", "fail", null, null, error = failure.describe())
            status("failed: ${failure.describe()}")
        } finally {
            peripheralDriver.stop()
        }
    }

    private suspend fun startPeripheral(scope: CoroutineScope): Int? {
        val listener = object : PeripheralListener {
            override fun onGattLink(peerAddress: String, link: GattChunkLink) {
                scope.launch { serve(peerAddress, link, Bearer.GATT) }
            }

            override fun onL2capLink(peerAddress: String, link: ByteLink) {
                scope.launch { serve(peerAddress, link, Bearer.L2CAP_INSECURE) }
            }

            override fun onEvent(name: String, peerAddress: String?, detail: String) {
                recorder.record("peripheral_event", "ok", null, local, extra = mapOf("event" to name, "peer_address" to peerAddress, "detail" to detail))
            }
        }
        val psm = peripheralDriver.start(identity, config.secureL2capServer, listener)
        recorder.record("peripheral_started", "ok", null, local, extra = mapOf("psm" to psm, "node_id" to identity.nodeIdHex))
        return psm
    }

    private suspend fun serve(peerAddress: String, link: ByteLink, bearer: Bearer) {
        activePeripheralLinks++
        var peer: PeerIdentity? = null
        recorder.record("peripheral_link_open", "ok", null, local, bearer = bearer.label, extra = mapOf("peer_address" to peerAddress, "active_links" to activePeripheralLinks))
        status("serving $activePeripheralLinks link(s)")
        try {
            Responder(MessagePipe(link), identity) { hello ->
                peer = hello
                if (bearer == Bearer.GATT) registry.onPeripheralLink(hello, link)
            }.run()
            recorder.record("peripheral_link_end", "ok", peer?.let { DeviceInfo.decode(it.info) }, local, bearer = bearer.label, extra = mapOf("reason" to "bye"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            recorder.record("peripheral_link_end", "closed", peer?.let { DeviceInfo.decode(it.info) }, local, bearer = bearer.label, error = failure.describe())
        } finally {
            activePeripheralLinks--
            peer?.let { if (bearer == Bearer.GATT) registry.onPeripheralLinkClosed(it, link) }
            status("serving $activePeripheralLinks link(s)")
        }
    }

    private suspend fun CoroutineScope.servePeripheral() {
        startPeripheral(this)
        status("advertising")
        delay(config.durationSeconds * 1000L)
    }

    private suspend fun runCentralAttempts() {
        val excluded = mutableSetOf<String>()
        var ok = 0
        for (attempt in 1..config.attempts) {
            status("attempt $attempt/${config.attempts} ok=$ok")
            if (CentralAttempt(config, centralDriver, recorder, identity, excluded, attempt).run()) ok++
            delay(1000)
        }
        recorder.record("central_summary", "ok", local, null, extra = mapOf("attempts" to config.attempts, "succeeded" to ok))
    }

    private suspend fun runHold() {
        var clock = PhaseClock()
        var connection: CentralConnection? = null
        var peer: PeerIdentity? = null
        var pipe: MessagePipe? = null
        for (attempt in 1..HOLD_CONNECT_ATTEMPTS) {
            clock = PhaseClock()
            try {
                val opened = withTimeout(config.scanTimeoutSeconds * 1000L + 30_000) {
                    centralDriver.connect(ConnectRequest(config.phy, emptySet(), config.scanTimeoutSeconds * 1000L), clock)
                }
                connection = opened
                peer = PeerIdentity.decodeControl(withTimeout(10_000) { opened.readControl() })
                pipe = MessagePipe(opened.gattLink).also {
                    withTimeout(10_000) {
                        it.send(MessageType.HELLO, identity.encodeHello())
                        it.expect(MessageType.HELLO)
                    }
                }
                break
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                recorder.record("hold_connect", "fail", local, null, bearer = "gatt", attempt = attempt, timings = clock.marks, error = "timeout")
            } catch (failure: Throwable) {
                recorder.record("hold_connect", "fail", local, null, bearer = "gatt", attempt = attempt, timings = clock.marks, error = failure.describe())
            }
            connection?.let { runCatching { it.disconnect() } }
            connection = null
            if (attempt == HOLD_CONNECT_ATTEMPTS) throw IllegalStateException("hold: no link after $attempt attempts")
            delay(2_000L * attempt)
        }
        connection!!
        pipe!!
        clock.mark("first_app_byte")
        val peerInfo = DeviceInfo.decode(peer!!.info)
        recorder.record("hold_connect", "ok", local, peerInfo, bearer = "gatt", mtu = connection.mtu, phy = connection.phyReport(), timings = clock.marks)
        status("holding")
        var echoes = 0
        var failure: String? = null
        val end = clock.elapsedMs() + config.durationSeconds * 1000.0
        while (clock.elapsedMs() < end && failure == null) {
            try {
                val sent = clock.elapsedMs()
                withTimeout(10_000) {
                    pipe.send(MessageType.ECHO, ByteArray(8))
                    pipe.expect(MessageType.ECHO)
                }
                echoes++
                // Periodic health record so a host can tell, mid-hold, that this link still echoes.
                if (echoes % HOLD_ALIVE_EVERY == 0) {
                    recorder.record(
                        "hold_alive", "ok", local, peerInfo, bearer = "gatt",
                        timings = mapOf("held_ms" to round3(clock.elapsedMs()), "last_rtt_ms" to round3(clock.elapsedMs() - sent)),
                        extra = mapOf("echoes" to echoes),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) failure = "echo timeout" else throw cancelled
            } catch (error: Throwable) {
                failure = error.describe()
            }
            delay(2000)
        }
        recorder.record(
            "hold_end", if (failure == null) "ok" else "fail", local, peerInfo, bearer = "gatt",
            timings = mapOf("held_ms" to round3(clock.elapsedMs())), error = failure, extra = mapOf("echoes" to echoes),
        )
        connection.disconnect()
    }

    private suspend fun runDual(scope: CoroutineScope) {
        startPeripheral(scope)
        DualRounds(config, centralDriver, recorder, identity, registry, status).run()
    }
}

fun Throwable.describe(): String = "${this::class.simpleName}: ${message ?: ""}".take(400)

private const val HOLD_CONNECT_ATTEMPTS = 5
private const val HOLD_ALIVE_EVERY = 3
