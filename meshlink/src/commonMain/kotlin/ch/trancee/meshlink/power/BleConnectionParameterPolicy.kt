package ch.trancee.meshlink.power

internal object BleConnectionParameterPolicy {
    enum class ConnectionState {
        BULK,
        ACTIVE,
        IDLE,
    }

    data class ConnectionParameters(val intervalMillis: Float, val slaveLatency: Int)

    val BULK = ConnectionParameters(intervalMillis = 7.5f, slaveLatency = 0)
    val ACTIVE = ConnectionParameters(intervalMillis = 15f, slaveLatency = 0)
    val IDLE = ConnectionParameters(intervalMillis = 100f, slaveLatency = 4)
}
