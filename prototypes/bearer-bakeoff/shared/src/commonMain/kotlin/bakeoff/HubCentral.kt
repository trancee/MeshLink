package bakeoff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Max-concurrency probe as central: keep dialling new peers while health-checking every held link. */
class HubCentral(
    private val config: BakeoffConfig,
    private val driver: CentralDriver,
    private val recorder: Recorder,
    private val identity: PeerIdentity,
    private val status: (String) -> Unit,
) {
    private class Held(val connection: CentralConnection, val pipe: MessagePipe, val info: DeviceInfo)

    private val held = mutableListOf<Held>()
    private val local = recorder.local

    suspend fun run() {
        val clock = PhaseClock()
        val excluded = mutableSetOf<String>()
        var maxConcurrent = 0
        var consecutiveFailures = 0
        var attempt = 0
        while (clock.elapsedMs() < config.durationSeconds * 1000.0 && consecutiveFailures < 3) {
            attempt++
            val attemptClock = PhaseClock()
            try {
                val excludedNow = excluded + held.map { it.connection.address }
                val connection = withTimeout(config.scanTimeoutSeconds * 1000L + 30_000) {
                    driver.connect(ConnectRequest(config.phy, excludedNow, config.scanTimeoutSeconds * 1000L), attemptClock)
                }
                val control = PeerIdentity.decodeControl(withTimeout(10_000) { connection.readControl() })
                if (control.runTag != config.runTag) {
                    excluded += connection.address
                    connection.disconnect()
                    continue
                }
                val pipe = MessagePipe(connection.gattLink)
                withTimeout(10_000) {
                    pipe.send(MessageType.HELLO, identity.encodeHello())
                    pipe.expect(MessageType.HELLO)
                }
                attemptClock.mark("first_app_byte")
                val info = DeviceInfo.decode(control.info)
                held += Held(connection, pipe, info)
                consecutiveFailures = 0
                recorder.record("hub_link_added", "ok", local, info, bearer = "gatt", mtu = connection.mtu, attempt = attempt, timings = attemptClock.marks, extra = mapOf("concurrent" to held.size))
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                consecutiveFailures++
                recorder.record("hub_link_added", "fail", local, null, bearer = "gatt", attempt = attempt, timings = attemptClock.marks, error = "timeout", extra = mapOf("concurrent" to held.size))
            } catch (failure: Throwable) {
                consecutiveFailures++
                recorder.record("hub_link_added", "fail", local, null, bearer = "gatt", attempt = attempt, timings = attemptClock.marks, error = failure.describe(), extra = mapOf("concurrent" to held.size))
            }
            healthCheck()
            maxConcurrent = maxOf(maxConcurrent, held.size)
            status("hub links=${held.size} max=$maxConcurrent")
        }
        repeat(6) {
            delay(5_000)
            healthCheck()
        }
        recorder.record(
            "max_concurrent", "ok", local, null, bearer = "gatt",
            extra = mapOf("max_concurrent" to maxConcurrent, "alive_after_30s" to held.size, "peers" to held.map { it.info.model }),
        )
        held.forEach { runCatching { it.connection.disconnect() } }
    }

    private suspend fun healthCheck() {
        val iterator = held.iterator()
        while (iterator.hasNext()) {
            val link = iterator.next()
            val error = try {
                withTimeout(5_000) {
                    link.pipe.send(MessageType.ECHO, ByteArray(8))
                    link.pipe.expect(MessageType.ECHO)
                }
                null
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                "echo timeout"
            } catch (failure: Throwable) {
                failure.describe()
            }
            if (error != null) {
                iterator.remove()
                runCatching { link.connection.disconnect() }
                recorder.record("hub_link_lost", "fail", local, link.info, bearer = "gatt", error = error, extra = mapOf("concurrent" to held.size))
            }
        }
    }
}
