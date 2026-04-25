package ch.trancee.meshlink.transfer

enum class Priority(val wire: Byte) {
    LOW(-1),
    NORMAL(0),
    HIGH(1);

    companion object {
        fun fromWire(b: Byte): Priority =
            when (b) {
                (-1).toByte() -> LOW
                (0).toByte() -> NORMAL
                (1).toByte() -> HIGH
                else -> NORMAL
            }
    }
}
