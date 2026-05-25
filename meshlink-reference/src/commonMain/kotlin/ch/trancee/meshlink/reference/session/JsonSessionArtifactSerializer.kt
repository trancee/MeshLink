package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.SessionArtifact
import ch.trancee.meshlink.reference.model.TimelineEntry

/** JSON serializer for redacted and explicit full-payload session exports. */
public class JsonSessionArtifactSerializer(private val documentStore: ReferenceDocumentStore) :
    SessionArtifactSerializer {
    override suspend fun serializeRedacted(
        artifact: SessionArtifact,
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String {
        return ReferenceJson.codec.encodeToString(
            ExportDocument.serializer(),
            buildExportDocument(
                artifact = artifact,
                session = session,
                peers = peers,
                timeline = timeline,
                includeFullPayload = false,
            ),
        )
    }

    override suspend fun serializeWithFullPayload(
        artifact: SessionArtifact,
        session: ReferenceSession,
        peers: List<PeerSnapshot>,
        timeline: List<TimelineEntry>,
    ): String {
        return ReferenceJson.codec.encodeToString(
            ExportDocument.serializer(),
            buildExportDocument(
                artifact = artifact,
                session = session,
                peers = peers,
                timeline = timeline,
                includeFullPayload = true,
            ),
        )
    }

    override suspend fun writeArtifact(artifact: SessionArtifact, serialized: String): String {
        documentStore.writeText(artifact.storagePath, serialized)
        return artifact.storagePath
    }
}
