package ch.trancee.meshlink.reference.model

internal fun referenceScenarioTitle(scenarioId: String): String {
    return when (scenarioId) {
        "guided-first-exchange" -> "Guided first exchange"
        "advanced-controls" -> "Advanced controls"
        "technical-timeline" -> "Technical timeline"
        "recent-history" -> "Recent history"
        "solo-exploration" -> "Solo exploration"
        "lab" -> "Lab"
        else ->
            scenarioId.split('-').joinToString(" ") { token ->
                token.replaceFirstChar { it.titlecase() }
            }
    }
}

internal fun referenceAuthorityLabel(authorityMode: ReferenceAuthorityMode): String {
    return when (authorityMode) {
        ReferenceAuthorityMode.LIVE -> "Live"
        ReferenceAuthorityMode.SOLO -> "Solo"
    }
}

internal fun referenceOutcomeLabel(summary: String?): String? {
    return when {
        summary == null -> null
        summary == "SendResult.Sent" -> "Message sent"
        summary == "StartResult.Started" -> "Mesh started"
        summary == "PauseResult.Paused" -> "Mesh paused"
        summary == "ResumeResult.Resumed" -> "Mesh resumed"
        summary == "StopResult.Stopped" -> "Mesh stopped"
        summary == "ForgetPeerResult.Forgotten" -> "Trust reset"
        summary == "Inbound message received" -> "Inbound message received"
        summary == "Large transfer complete" -> "Large transfer complete"
        summary.contains("PAYLOAD_TOO_LARGE") -> "Payload too large"
        summary.contains("TRANSFER_TIMED_OUT") -> "Transfer timed out"
        summary.contains("TRANSFER_ABORTED") -> "Transfer aborted"
        summary.contains("UNREACHABLE") -> "Peer unreachable"
        summary.contains("TRUST_FAILURE") -> "Trust check failed"
        else -> summary
    }
}
