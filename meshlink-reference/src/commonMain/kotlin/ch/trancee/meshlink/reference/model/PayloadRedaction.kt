package ch.trancee.meshlink.reference.model

internal fun redactedPayloadPreview(payloadText: String): String {
    if (payloadText.isEmpty()) {
        return "<redacted empty payload>"
    }
    val visiblePrefix = payloadText.take(VISIBLE_PREFIX_CHARACTERS)
    return if (payloadText.length <= VISIBLE_PREFIX_CHARACTERS) {
        "$visiblePrefix… [redacted]"
    } else {
        "$visiblePrefix… [${payloadText.length} chars total, redacted]"
    }
}

internal fun TimelineEntry.withoutSensitivePayload(): TimelineEntry {
    return copy(fullPayload = null, fullPayloadIncluded = false)
}

private const val VISIBLE_PREFIX_CHARACTERS: Int = 2
