package ch.trancee.meshlink.transfer

internal object ResumeCalculator {
    /** Returns the byte offset aligned down to the nearest [chunkSize] boundary. */
    fun alignedOffset(bytesReceived: Long, chunkSize: Int): Long =
        (bytesReceived / chunkSize) * chunkSize
}
