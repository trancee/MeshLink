package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry

/** Serialization contract for redacted and opt-in session exports. */
internal interface SessionArtifactSerializer {
    public suspend fun serializeRedacted(
        artifact: SessionArtifact,
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String

    public suspend fun serializeWithFullPayload(
        artifact: SessionArtifact,
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String

    public suspend fun writeArtifact(artifact: SessionArtifact, serialized: String): String
}
