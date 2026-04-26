package ch.trancee.meshlink.transfer

/**
 * Observation-based send-rate controller.
 *
 * Rate starts at 1 chunk per round-trip. After [TransferConfig.acksBeforeDouble] consecutive ACKs,
 * rate doubles to 2. After [TransferConfig.acksBeforeQuad] additional consecutive ACKs at rate 2,
 * rate quadruples to 4 (maximum). Any timeout resets the rate to 1.
 */
internal class ObservationRateController(private val config: TransferConfig) {
    private var consecutiveAcks: Int = 0
    private var rate: Int = 1

    /** Called on each received ACK. Advances the rate when thresholds are met. */
    fun onAck() {
        consecutiveAcks++
        when {
            rate == 1 && consecutiveAcks >= config.acksBeforeDouble -> {
                rate = 2
                consecutiveAcks = 0
            }
            rate == 2 && consecutiveAcks >= config.acksBeforeQuad -> {
                rate = 4
            }
        }
    }

    /** Called on an ACK timeout. Resets rate to 1 and clears the consecutive ACK counter. */
    fun onTimeout() {
        rate = 1
        consecutiveAcks = 0
    }

    /** Current number of chunks the sender should transmit per round-trip. */
    fun currentRate(): Int = rate
}
