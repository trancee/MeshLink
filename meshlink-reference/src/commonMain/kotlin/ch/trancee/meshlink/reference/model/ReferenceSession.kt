package ch.trancee.meshlink.reference.model

import ch.trancee.meshlink.api.MeshLinkState
import kotlinx.serialization.Serializable

/**
 * Captures the visible runtime state of one reference-app session.
 */
@Serializable
public data class ReferenceSession(
    public val sessionId: String,
    public val scenarioId: String,
    public val authorityMode: ReferenceAuthorityMode,
    public val startedAtEpochMillis: Long,
    public val endedAtEpochMillis: Long? = null,
    public val meshStateLabel: String = MeshLinkState.Uninitialized.toString(),
    public val selectedPeerId: String? = null,
    public val configurationSnapshot: Map<String, String> = emptyMap(),
    public val lastOutcomeSummary: String? = null,
    public val historyStatus: ReferenceHistoryStatus = ReferenceHistoryStatus.LIVE,
)

@Serializable
public enum class ReferenceAuthorityMode {
    LIVE,
    SOLO,
}

@Serializable
public enum class ReferenceHistoryStatus {
    LIVE,
    RETAINED,
    EXPORTED,
    DELETED,
}
