package ch.trancee.meshlink.power

object BleConnectionParameterPolicy {
    enum class ConnectionState {
        BULK,
        ACTIVE,
        IDLE,
    }

    data class ConnectionParameters(val intervalMs: Float, val slaveLatency: Int)

    val BULK = ConnectionParameters(intervalMs = 7.5f, slaveLatency = 0)
    val ACTIVE = ConnectionParameters(intervalMs = 15f, slaveLatency = 0)
    val IDLE = ConnectionParameters(intervalMs = 100f, slaveLatency = 4)
}
