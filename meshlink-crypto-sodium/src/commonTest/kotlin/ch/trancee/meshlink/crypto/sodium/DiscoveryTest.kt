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
        meshKey: ByteArray? = null,
    ) = Discovery(SodiumCryptoProvider, meshId, profile, meshKey)

    @Test
    fun aGatedMeshTagsWithTheMeshTagKey() {
        // Arrange
        val derivations = Derivations(SodiumCryptoProvider)
        val tagKey = derivations.meshTagKey(MESH_KEY, derivations.meshIdDigest(meshId, WireProfile.CLASSICAL))
        val gated = discovery(profile = WireProfile.CLASSICAL, meshKey = MESH_KEY)

        // Act
        val payload = gated.advertisement(EPOCH)

        // Assert
        val advertisement = (Advertisement.decode(payload) as Decoded.Valid).value
        assertContentEquals(derivations.meshTag(tagKey, EPOCH), advertisement.meshTag)
    }

    @Test
    fun aResolvingKeyMakesTheResolvableTagOfTheEpoch() {
        // Arrange
        val gated = discovery(profile = WireProfile.CLASSICAL, meshKey = MESH_KEY)
        val resolvingKey = ByteArray(32) { 0x22 }

        // Act
        val first = Advertisement.decode(gated.advertisement(EPOCH, resolvingKey)) as Decoded.Valid
        val second = Advertisement.decode(gated.advertisement(EPOCH, resolvingKey)) as Decoded.Valid

        // Assert
        val tag = Derivations(SodiumCryptoProvider).resolvableTag(resolvingKey, EPOCH)
        assertContentEquals(tag, first.value.resolvableTag)
        assertContentEquals(tag, second.value.resolvableTag)
        assertFalse(first.value.random.contentEquals(second.value.random))
    }

    @Test
    fun aGatedMeshKeepsOnlyMembersWithTheSameMeshKey() {
        val gated = discovery(profile = WireProfile.CLASSICAL, meshKey = MESH_KEY)
        val sameKey = discovery(profile = WireProfile.CLASSICAL, meshKey = MESH_KEY.copyOf())
        val otherKey = discovery(profile = WireProfile.CLASSICAL, meshKey = ByteArray(MESH_KEY.size))
        val open = discovery(profile = WireProfile.CLASSICAL)

        assertNotNull(gated.accept(sameKey.advertisement(EPOCH), EPOCH))
        assertNull(gated.accept(otherKey.advertisement(EPOCH), EPOCH))
        assertNull(gated.accept(open.advertisement(EPOCH), EPOCH))
        assertNull(open.accept(gated.advertisement(EPOCH), EPOCH))
    }

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
    fun advertisementCarriesTheFlagsItIsGiven() {
        val payload = node.advertisement(EPOCH, flags = Advertisement.COC_AVAILABLE)

        val advertisement = (Advertisement.decode(payload) as Decoded.Valid).value
        assertEquals(Advertisement.COC_AVAILABLE, advertisement.flags)
        assertNotNull(member.accept(payload, EPOCH))
    }

    @Test
    fun anIdentifierIsTheResolvableTagOfTheEpochWithAKeyAndFreshRandomBytesWithout() {
        // Arrange
        val resolvingKey = ByteArray(32) { 0x22 }

        // Act
        val resolvable = node.identifier(EPOCH, resolvingKey)
        val first = node.identifier(EPOCH)
        val second = node.identifier(EPOCH)

        // Assert
        assertContentEquals(Derivations(SodiumCryptoProvider).resolvableTag(resolvingKey, EPOCH), resolvable)
        assertEquals(Advertisement.RESOLVABLE_TAG_LENGTH, first.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun anAdvertisementCarriesTheIdentifierItIsGivenWithFreshFill() {
        // Arrange
        val identifier = node.identifier(EPOCH)

        // Act
        val first = Advertisement.decode(node.advertisement(EPOCH, identifier = identifier)) as Decoded.Valid
        val second = Advertisement.decode(node.advertisement(EPOCH, identifier = identifier)) as Decoded.Valid

        // Assert
        assertContentEquals(identifier, first.value.resolvableTag)
        assertContentEquals(identifier, second.value.resolvableTag)
        assertFalse(first.value.random.contentEquals(second.value.random))
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
        val MESH_KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
