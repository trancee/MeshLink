package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Advertisement
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.Discovery
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiscoveryTest {
    private val meshId = "Festival".encodeToByteArray()
    private val node = discovery()
    private val member = discovery()

    private fun discovery(
        meshId: ByteArray = this.meshId,
        profile: WireProfile = WireProfile.UNSECURED,
    ) = Discovery(SodiumCryptoProvider, meshId, profile)

    @Test
    fun unsecuredAdvertisementCarriesTheMeshTagOfTheUnsecuredMeshIdDigestAndNoFlags() {
        // Arrange
        val derivations = Derivations(SodiumCryptoProvider)
        val tagKey = derivations.meshIdDigest(meshId, WireProfile.UNSECURED)

        // Act
        val payload = node.advertisement(EPOCH)

        // Assert
        val advertisement = (Advertisement.decode(payload) as Decoded.Valid).value
        assertEquals(0, advertisement.flags)
        assertContentEquals(derivations.meshTag(tagKey, EPOCH), advertisement.meshTag)
    }

    @Test
    fun eachAdvertisementDrawsAFreshResolvableTagAndRandomFill() {
        val first = Advertisement.decode(node.advertisement(EPOCH)) as Decoded.Valid
        val second = Advertisement.decode(node.advertisement(EPOCH)) as Decoded.Valid

        assertFalse(first.value.resolvableTag.contentEquals(second.value.resolvableTag))
        assertFalse(first.value.random.contentEquals(second.value.random))
    }

    @Test
    fun keepsAMemberAdvertisingInTheEpochBeforeTheCurrentOrAfter() {
        listOf(EPOCH - 1u, EPOCH, EPOCH + 1u).forEach { epoch ->
            val payload = member.advertisement(epoch)

            val accepted = node.accept(payload, EPOCH)

            assertContentEquals(payload, assertNotNull(accepted, "epoch $epoch").encode())
        }
    }

    @Test
    fun dropsAMemberAdvertisingTwoEpochsAway() {
        assertNull(node.accept(member.advertisement(EPOCH - 2u), EPOCH))
        assertNull(node.accept(member.advertisement(EPOCH + 2u), EPOCH))
    }

    @Test
    fun followsTheCurrentEpoch() {
        val payload = member.advertisement(EPOCH + 3u)

        val before = node.accept(payload, EPOCH)
        val after = node.accept(payload, EPOCH + 3u)

        assertNull(before)
        assertNotNull(after)
    }

    @Test
    fun dropsAnotherMeshIdAndTheSameMeshIdWithAnotherProfile() {
        val otherMesh = discovery(meshId = "Festival2".encodeToByteArray())
        val otherProfile = discovery(profile = WireProfile.CLASSICAL)

        assertNull(node.accept(otherMesh.advertisement(EPOCH), EPOCH))
        assertNull(node.accept(otherProfile.advertisement(EPOCH), EPOCH))
    }

    @Test
    fun dropsItsOwnAdvertisement() {
        val own = node.advertisement(EPOCH)

        assertNull(node.accept(own, EPOCH))
    }

    @Test
    fun dropsAPayloadThatDoesNotDecode() {
        val payload = member.advertisement(EPOCH).copyOf(RANDOMLESS_LENGTH)

        assertNull(node.accept(payload, EPOCH))
    }

    private companion object {
        val EPOCH: UInt = Derivations.epoch(1_767_225_600)
        const val RANDOMLESS_LENGTH = 13
    }
}
