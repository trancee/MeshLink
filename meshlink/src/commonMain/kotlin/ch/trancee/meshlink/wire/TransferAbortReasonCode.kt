package ch.trancee.meshlink.wire

internal enum class TransferAbortReasonCode(internal val code: Int) {
    RUNTIME_STOPPED(1);

    internal companion object {
        internal fun fromCode(code: Int): TransferAbortReasonCode? {
            return entries.firstOrNull { reason -> reason.code == code }
        }
    }
}
