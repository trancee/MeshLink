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

internal fun buildMeshEngineRuntimeDiscoverySuspensionSupport(
    setDiscoverySuspended: suspend (String, Boolean) -> Unit,
    suspendAction: String,
    resumeAction: String,
): MeshEngineDiscoverySuspensionSupport {
    return MeshEngineDiscoverySuspensionSupport { suspended ->
        val action = if (suspended) suspendAction else resumeAction
        setDiscoverySuspended(action, suspended)
    }
}
