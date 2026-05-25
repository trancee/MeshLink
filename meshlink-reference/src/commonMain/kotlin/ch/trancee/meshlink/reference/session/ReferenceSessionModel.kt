package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode

public enum class ReferenceSessionKind {
    SUPPORTED_LIVE,
    SUPPORTED_ENDED,
    SOLO,
    LAB,
}

internal fun ReferenceControllerSnapshot.referenceSessionKind(): ReferenceSessionKind {
    val surfaceOfOrigin = session.configurationSnapshot["surface"]
    return when {
        session.authorityMode == ReferenceAuthorityMode.SOLO -> ReferenceSessionKind.SOLO
        surfaceOfOrigin == "lab" -> ReferenceSessionKind.LAB
        session.endedAtEpochMillis != null -> ReferenceSessionKind.SUPPORTED_ENDED
        else -> ReferenceSessionKind.SUPPORTED_LIVE
    }
}

internal fun ReferenceControllerSnapshot.allowsFullPayloadExport(): Boolean {
    return referenceSessionKind() == ReferenceSessionKind.SUPPORTED_LIVE
}

internal fun ReferenceControllerSnapshot.withSurfaceOfOrigin(
    surfaceOfOrigin: String
): ReferenceControllerSnapshot {
    return copy(
        session =
            session.copy(
                configurationSnapshot =
                    session.configurationSnapshot + mapOf("surface" to surfaceOfOrigin)
            )
    )
}
