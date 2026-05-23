package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerLinkLifecycleTest {
    @Test
    fun `temporary hint promotion returns the active temporary hint when canonical hint is inactive`() {
        // Arrange
        val request =
            TemporaryPeerHintPromotionRequest(
                temporaryHintPeerIdValue = "cb-temporary1234",
                resolvedHintPeerIdValue = "peer12345678",
                activeHintIds = setOf("cb-temporary1234"),
                temporaryPeerPrefix = "cb-",
            )

        // Act
        val selectedHint = selectTemporaryPeerHintPromotion(request)

        // Assert
        assertEquals("cb-temporary1234", selectedHint)
    }

    @Test
    fun `temporary hint promotion returns null when canonical hint already has an active link`() {
        // Arrange
        val request =
            TemporaryPeerHintPromotionRequest(
                temporaryHintPeerIdValue = "cb-temporary1234",
                resolvedHintPeerIdValue = "peer12345678",
                activeHintIds = setOf("cb-temporary1234", "peer12345678"),
                temporaryPeerPrefix = "cb-",
            )

        // Act
        val selectedHint = selectTemporaryPeerHintPromotion(request)

        // Assert
        assertNull(selectedHint)
    }

    @Test
    fun `active peer hint resolution prefers the canonical hint before a temporary hint`() {
        // Arrange
        val request =
            ActivePeerHintResolutionRequest(
                hintPeerIdValue = "peer12345678",
                temporaryHintPeerIdValue = "cb-temporary1234",
                activeHintIds = setOf("peer12345678", "cb-temporary1234"),
            )

        // Act
        val activeHint = resolveActivePeerHint(request)

        // Assert
        assertEquals("peer12345678", activeHint)
    }

    @Test
    fun `active peer hint resolution falls back to the temporary hint when canonical hint is inactive`() {
        // Arrange
        val request =
            ActivePeerHintResolutionRequest(
                hintPeerIdValue = "peer12345678",
                temporaryHintPeerIdValue = "cb-temporary1234",
                activeHintIds = setOf("cb-temporary1234"),
            )

        // Act
        val activeHint = resolveActivePeerHint(request)

        // Assert
        assertEquals("cb-temporary1234", activeHint)
    }

    @Test
    fun `rediscovery decision logs once when an l2cap peer is rediscovered without any active or pending link`() {
        // Arrange
        val request =
            RediscoveryWithoutLinkDecisionRequest(
                transportMode = TransportMode.L2CAP,
                hintPeerIdValue = "peer12345678",
                temporaryHintPeerIdValue = "cb-temporary1234",
                activeHintIds = emptySet(),
                hasActiveSideLink = false,
                hasPendingConnect = false,
                rediscoveryLoggedWithoutLink = false,
            )

        // Act
        val decision = evaluateRediscoveryWithoutLink(request)

        // Assert
        assertTrue(decision.shouldLogRediscoveryWithoutLink)
        assertTrue(decision.rediscoveryLoggedWithoutLink)
    }

    @Test
    fun `rediscovery decision clears the logged flag when a side link is still active`() {
        // Arrange
        val request =
            RediscoveryWithoutLinkDecisionRequest(
                transportMode = TransportMode.L2CAP,
                hintPeerIdValue = "peer12345678",
                temporaryHintPeerIdValue = "cb-temporary1234",
                activeHintIds = emptySet(),
                hasActiveSideLink = true,
                hasPendingConnect = false,
                rediscoveryLoggedWithoutLink = true,
            )

        // Act
        val decision = evaluateRediscoveryWithoutLink(request)

        // Assert
        assertFalse(decision.shouldLogRediscoveryWithoutLink)
        assertFalse(decision.rediscoveryLoggedWithoutLink)
    }

    @Test
    fun `rediscovery decision suppresses repeat logging after the first disconnected rediscovery`() {
        // Arrange
        val request =
            RediscoveryWithoutLinkDecisionRequest(
                transportMode = TransportMode.L2CAP,
                hintPeerIdValue = "peer12345678",
                temporaryHintPeerIdValue = "cb-temporary1234",
                activeHintIds = emptySet(),
                hasActiveSideLink = false,
                hasPendingConnect = false,
                rediscoveryLoggedWithoutLink = true,
            )

        // Act
        val decision = evaluateRediscoveryWithoutLink(request)

        // Assert
        assertFalse(decision.shouldLogRediscoveryWithoutLink)
        assertTrue(decision.rediscoveryLoggedWithoutLink)
    }
}
