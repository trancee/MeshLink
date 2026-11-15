package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519SignatureTest {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    // RFC 8032 section 7.1, TEST 1 and TEST 2.
    private val seed1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex()
    private val publicKey1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hex()
    private val signature1 =
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b" +
            "d25bf5f0595bbe24655141438e7a100b"
    private val seed2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hex()
    private val publicKey2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".hex()
    private val message2 = "72".hex()
    private val signature2 =
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c" +
            "387b2eaeb4302aeeb00d291612bb0c00"

    @Test
    fun signaturesMatchRfc8032() {
        assertEquals(signature1, crypto.ed25519Sign(seed1, ByteArray(0)).hex())
        assertEquals(signature2, crypto.ed25519Sign(seed2, message2).hex())
    }

    @Test
    fun rfc8032SignaturesVerify() {
        assertTrue(crypto.ed25519Verify(publicKey1, ByteArray(0), signature1.hex()))
        assertTrue(crypto.ed25519Verify(publicKey2, message2, signature2.hex()))
    }

    @Test
    fun everyFlippedSignatureBitFailsVerification() {
        val signature = signature2.hex()

        for (bit in 0 until signature.size * 8) {
            val flipped = signature.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl bit % 8)).toByte() }
            assertFalse(crypto.ed25519Verify(publicKey2, message2, flipped), "bit $bit")
        }
    }

    @Test
    fun wrongKeyOrMessageFailsVerification() {
        assertFalse(crypto.ed25519Verify(publicKey1, message2, signature2.hex()))
        assertFalse(crypto.ed25519Verify(publicKey2, "73".hex(), signature2.hex()))
        assertFalse(crypto.ed25519Verify(publicKey2, ByteArray(0), signature2.hex()))
    }

    @Test
    fun signatureOfWrongLengthIsInvalid() {
        assertFalse(crypto.ed25519Verify(publicKey2, message2, signature2.hex().copyOf(63)))
        assertFalse(crypto.ed25519Verify(publicKey2, message2, signature2.hex().copyOf(65)))
    }

    @Test
    fun wrongLengthKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { crypto.ed25519Sign(ByteArray(31), message2) }
        assertFailsWith<IllegalArgumentException> { crypto.ed25519Sign(ByteArray(64), message2) }
        assertFailsWith<IllegalArgumentException> {
            crypto.ed25519Verify(ByteArray(31), message2, signature2.hex())
        }
    }
}
