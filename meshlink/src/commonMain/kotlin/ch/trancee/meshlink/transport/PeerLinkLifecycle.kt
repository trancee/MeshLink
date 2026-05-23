package ch.trancee.meshlink.transport

internal class TemporaryPeerHintPromotionRequest(
    val temporaryHintPeerIdValue: String?,
    val resolvedHintPeerIdValue: String,
    val activeHintIds: Collection<String>,
    val temporaryPeerPrefix: String,
)

internal fun selectTemporaryPeerHintPromotion(request: TemporaryPeerHintPromotionRequest): String? {
    val temporaryHintPeerIdValue = request.temporaryHintPeerIdValue
    val canPromote =
        temporaryHintPeerIdValue != null &&
            temporaryHintPeerIdValue != request.resolvedHintPeerIdValue &&
            temporaryHintPeerIdValue.startsWith(request.temporaryPeerPrefix) &&
            temporaryHintPeerIdValue in request.activeHintIds &&
            request.resolvedHintPeerIdValue !in request.activeHintIds
    return if (canPromote) temporaryHintPeerIdValue else null
}

internal class ActivePeerHintResolutionRequest(
    val hintPeerIdValue: String,
    val temporaryHintPeerIdValue: String?,
    val activeHintIds: Collection<String>,
)

internal fun resolveActivePeerHint(request: ActivePeerHintResolutionRequest): String? {
    return when {
        request.hintPeerIdValue in request.activeHintIds -> request.hintPeerIdValue
        request.temporaryHintPeerIdValue != null &&
            request.temporaryHintPeerIdValue in request.activeHintIds ->
            request.temporaryHintPeerIdValue
        else -> null
    }
}

internal class RediscoveryWithoutLinkDecisionRequest(
    val transportMode: TransportMode,
    val hintPeerIdValue: String,
    val temporaryHintPeerIdValue: String?,
    val activeHintIds: Collection<String>,
    val hasActiveSideLink: Boolean,
    val hasPendingConnect: Boolean,
    val rediscoveryLoggedWithoutLink: Boolean,
)

internal class RediscoveryWithoutLinkDecision(
    val shouldLogRediscoveryWithoutLink: Boolean,
    val rediscoveryLoggedWithoutLink: Boolean,
)

internal fun evaluateRediscoveryWithoutLink(
    request: RediscoveryWithoutLinkDecisionRequest
): RediscoveryWithoutLinkDecision {
    if (request.transportMode != TransportMode.L2CAP) {
        return RediscoveryWithoutLinkDecision(
            shouldLogRediscoveryWithoutLink = false,
            rediscoveryLoggedWithoutLink = false,
        )
    }

    val hasActiveLink =
        resolveActivePeerHint(
            ActivePeerHintResolutionRequest(
                hintPeerIdValue = request.hintPeerIdValue,
                temporaryHintPeerIdValue = request.temporaryHintPeerIdValue,
                activeHintIds = request.activeHintIds,
            )
        ) != null
    if (hasActiveLink || request.hasActiveSideLink || request.hasPendingConnect) {
        return RediscoveryWithoutLinkDecision(
            shouldLogRediscoveryWithoutLink = false,
            rediscoveryLoggedWithoutLink = false,
        )
    }

    return if (request.rediscoveryLoggedWithoutLink) {
        RediscoveryWithoutLinkDecision(
            shouldLogRediscoveryWithoutLink = false,
            rediscoveryLoggedWithoutLink = true,
        )
    } else {
        RediscoveryWithoutLinkDecision(
            shouldLogRediscoveryWithoutLink = true,
            rediscoveryLoggedWithoutLink = true,
        )
    }
}
