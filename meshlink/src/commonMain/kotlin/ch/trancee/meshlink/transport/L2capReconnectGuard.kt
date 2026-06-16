package ch.trancee.meshlink.transport

internal class L2capReconnectGuard {
    private val retriedHintPeerIds: MutableSet<String> = linkedSetOf()

    internal fun shouldRetry(hintPeerIdValue: String, reason: String): Boolean {
        if (!reason.isTransientL2capDisconnect()) {
            return false
        }
        return retriedHintPeerIds.add(hintPeerIdValue)
    }

    internal fun clear(): Unit {
        retriedHintPeerIds.clear()
    }
}

internal fun String.isTransientL2capDisconnect(): Boolean {
    return startsWith("socket closed") || startsWith("send failed:")
}
