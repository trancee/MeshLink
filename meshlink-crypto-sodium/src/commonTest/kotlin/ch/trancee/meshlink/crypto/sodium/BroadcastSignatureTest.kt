package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.BroadcastFrame
import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.FloodHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BroadcastSignatureTest {
    private val crypto = SodiumCryptoProvider
    private val identity = crypto.ed25519KeyPair(ByteArray(32) { 1 })
    private val other = crypto.ed25519KeyPair(ByteArray(32) { 2 })
    private val nodeId = Derivations(crypto).nodeId(identity.publicKey)
    private val header = FloodHeader(64, ByteArray(12) { 7 }, 1_798_762_200u)
    private val payload = "Water at gate 3".encodeToByteArray()

    private fun signed(): ByteArray = BroadcastFrame.sign(crypto, header, identity, payload).encode()

    private fun decode(bytes: ByteArray): BroadcastFrame {
        val decoded = BroadcastFrame.decode(bytes)
        return assertIs<Decoded.Valid<BroadcastFrame>>(decoded).value
    }

    private fun ByteArray.flipped(index: Int): ByteArray {
        val copy = copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    @Test
    fun aSignedBroadcastVerifiesToItsOriginsNodeIdAtAnyHopLimit() {
        val bytes = signed()
        val lowered = bytes.copyOf().also { it[1] = 1 }

        assertContentEquals(nodeId, decode(bytes).verifiedOrigin(crypto))
        assertContentEquals(nodeId, decode(lowered).verifiedOrigin(crypto))
        assertContentEquals(payload, assertIs<Container.SignedBroadcast>(decode(bytes).container).payload)
    }

    @Test
    fun aFlippedPayloadSignatureMessageIdOrExpiryBitFailsVerification() {
        val bytes = signed()

        for (index in listOf(bytes.size - 65, bytes.size - 1, 2, 17)) {
            assertNull(decode(bytes.flipped(index)).verifiedOrigin(crypto), "byte $index")
        }
    }

    @Test
    fun anOriginKeySwappedForAnotherIdentitysFailsVerification() {
        val swapped = signed().also { other.publicKey.copyInto(it, FloodHeader.LENGTH + 1) }

        assertNull(decode(swapped).verifiedOrigin(crypto))
    }

    @Test
    fun anUnsecuredBroadcastHasNoVerifiedOrigin() {
        val frame = BroadcastFrame(header, Container.Unsecured(nodeId, payload))

        assertNull(frame.verifiedOrigin(crypto))
    }

    @Test
    fun theSigningInputLeavesOutTheHopLimitAndTheSignature() {
        val bytes = signed()
        val unsigned = bytes.copyOf(bytes.size - 64)
        val expected =
            byteArrayOf(20) + "MeshLink/1 broadcast".encodeToByteArray() + unsigned[0] +
                unsigned.copyOfRange(2, unsigned.size)

        assertContentEquals(expected, BroadcastFrame.signingInput(unsigned))
    }
}
