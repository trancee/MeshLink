package ch.trancee.meshlink.engine

internal class MeshEngineDiscoverySuspensionSupport(
    private val setDiscoverySuspended: suspend (Boolean) -> Unit
) {
    suspend fun <T> withDiscoverySuspended(
        shouldSuspend: Boolean = true,
        block: suspend () -> T,
    ): T {
        if (!shouldSuspend) {
            return block()
        }
        setDiscoverySuspended(true)
        return try {
            block()
        } finally {
            setDiscoverySuspended(false)
        }
    }
}
