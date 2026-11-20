package ch.trancee.meshlink.internal

/** Route sequence numbers (spec "Rules"): `u32`, compared by RFC 1982 serial-number arithmetic, 0 skipped. */
internal object Sequence {
    /**
     * Whether [a] is newer than [b]: it is less than half the number space ahead, wrapping. Two
     * sequences exactly half apart are undefined in RFC 1982, so neither is newer.
     */
    fun newer(
        a: UInt,
        b: UInt,
    ): Boolean = (a - b).toInt() > 0

    /** The sequence after [sequence]. */
    fun next(sequence: UInt): UInt = if (sequence == UInt.MAX_VALUE) 1u else sequence + 1u
}
