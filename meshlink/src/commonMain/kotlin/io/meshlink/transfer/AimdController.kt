package io.meshlink.transfer

class AimdController(private val initialWindow: Int = 1) {
    var window: Int = initialWindow
        private set

    private var cleanRounds: Int = 0
    private var consecutiveTimeouts: Int = 0

    fun onAck() {
        consecutiveTimeouts = 0
        cleanRounds++
        if (cleanRounds >= 4) {
            window += 2
            cleanRounds = 0
        }
    }

    fun onTimeout() {
        cleanRounds = 0
        consecutiveTimeouts++
        if (consecutiveTimeouts >= 2) {
            window = (window / 2).coerceAtLeast(1)
            consecutiveTimeouts = 0
        }
    }

    fun onReconnect() {
        window = initialWindow
        cleanRounds = 0
        consecutiveTimeouts = 0
    }
}
