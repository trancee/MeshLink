package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.allowsFullPayloadExport

internal fun normalizeExportPolicy(
    snapshot: ReferenceControllerSnapshot,
    requestedPolicy: ExportPayloadPolicy,
): ExportPayloadPolicy {
    return if (snapshot.allowsFullPayloadExport()) {
        requestedPolicy
    } else {
        ExportPayloadPolicy.REDACTED_PREVIEW
    }
}
