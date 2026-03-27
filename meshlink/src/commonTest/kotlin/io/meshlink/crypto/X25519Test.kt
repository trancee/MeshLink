package io.meshlink.crypto

import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class X25519Test {

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.toHex()
    }

    // --- RFC 7748 Section 6.1 test vectors ---

    @Test
    fun testAlicePublicKey() {
        val alicePrivate = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expectedPub = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

        val basePoint = ByteArray(32).also { it[0] = 9 }
        val publicKey = X25519.scalarMult(alicePrivate, basePoint)

        assertContentEquals(expectedPub, publicKey, "Alice public key mismatch")
    }

    @Test
    fun testBobPublicKey() {
        val bobPrivate = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expectedPub = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        val basePoint = ByteArray(32).also { it[0] = 9 }
        val publicKey = X25519.scalarMult(bobPrivate, basePoint)

        assertContentEquals(expectedPub, publicKey, "Bob public key mismatch")
    }

    @Test
    fun testSharedSecretAliceSide() {
        val alicePrivate = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPublic = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedSecret = hexToBytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        val shared = X25519.sharedSecret(alicePrivate, bobPublic)

        assertContentEquals(expectedSecret, shared, "Shared secret (Alice side) mismatch")
    }

    @Test
    fun testSharedSecretBobSide() {
        val bobPrivate = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val expectedSecret = hexToBytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        val shared = X25519.sharedSecret(bobPrivate, alicePublic)

        assertContentEquals(expectedSecret, shared, "Shared secret (Bob side) mismatch")
    }

    @Test
    fun testSharedSecretCommutativity() {
        val alicePrivate = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPrivate = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val alicePublic = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

        val sharedAB = X25519.sharedSecret(alicePrivate, bobPublic)
        val sharedBA = X25519.sharedSecret(bobPrivate, alicePublic)

        assertContentEquals(sharedAB, sharedBA, "A_priv × B_pub must equal B_priv × A_pub")
    }

    // --- Key generation tests ---

    @Test
    fun testGenerateKeyPairProduces32ByteKeys() {
        val keyPair = X25519.generateKeyPair()

        assertEquals(32, keyPair.publicKey.size, "Public key must be 32 bytes")
        assertEquals(32, keyPair.privateKey.size, "Private key must be 32 bytes")
    }

    @Test
    fun testGeneratedKeyPairCommutativity() {
        val alice = X25519.generateKeyPair()
        val bob = X25519.generateKeyPair()

        val sharedAB = X25519.sharedSecret(alice.privateKey, bob.publicKey)
        val sharedBA = X25519.sharedSecret(bob.privateKey, alice.publicKey)

        assertContentEquals(sharedAB, sharedBA, "Generated key pair shared secret must be commutative")
    }

    // --- Clamping tests ---

    @Test
    fun testPrivateKeyClamping() {
        val keyPair = X25519.generateKeyPair()
        val priv = keyPair.privateKey

        // Bits 0, 1, 2 of first byte must be clear
        assertEquals(0, priv[0].toInt() and 7, "Bits 0,1,2 of first byte must be cleared")

        // Bit 7 of last byte must be clear
        assertEquals(0, priv[31].toInt() and 128, "Bit 7 of last byte must be cleared")

        // Bit 6 of last byte must be set
        assertEquals(64, priv[31].toInt() and 64, "Bit 6 of last byte must be set")
    }

    // --- RFC 7748 Section 5.2 function test vectors ---

    @Test
    fun testVector1ArbitraryScalarMult() {
        val scalar = hexToBytes("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoord = hexToBytes("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = hexToBytes("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")

        val result = X25519.scalarMult(scalar, uCoord)
        assertContentEquals(expected, result, "RFC 7748 §5.2 vector 1 mismatch")
    }

    @Test
    fun testVector2ArbitraryScalarMult() {
        val scalar = hexToBytes("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val uCoord = hexToBytes("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val expected = hexToBytes("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957")

        val result = X25519.scalarMult(scalar, uCoord)
        assertContentEquals(expected, result, "RFC 7748 §5.2 vector 2 mismatch")
    }

    // --- RFC 7748 Section 5.2 iterated test vectors ---

    @Test
    fun testIteratedScalarMultiplication1() {
        var k = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")
        var u = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")

        val result = X25519.scalarMult(k, u)
        k = result

        val expectedAfter1 = hexToBytes("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079")
        assertContentEquals(expectedAfter1, k, "Iterated result after 1 iteration mismatch")
    }

    @Test
    fun testIteratedScalarMultiplication1000() {
        var k = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")
        var u = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")

        for (i in 0 until 1000) {
            val result = X25519.scalarMult(k, u)
            u = k.copyOf()
            k = result
        }

        val expectedAfter1000 = hexToBytes("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51")
        assertContentEquals(expectedAfter1000, k, "Iterated result after 1000 iterations mismatch")
    }

    // RFC 7748 §5.2: 1,000,000 iteration vector — disabled by default (takes minutes in pure Kotlin)
    // Uncomment to run as a thorough conformance check.
    // @Test
    // fun testIteratedScalarMultiplication1000000() {
    //     var k = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")
    //     var u = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")
    //
    //     for (i in 0 until 1_000_000) {
    //         val result = X25519.scalarMult(k, u)
    //         u = k.copyOf()
    //         k = result
    //     }
    //
    //     val expectedAfter1M = hexToBytes("7c3911e0ab2586fd864497297e575e6f3bc601c0883c30df5f4dd2d24f665424")
    //     assertContentEquals(expectedAfter1M, k, "Iterated result after 1,000,000 iterations mismatch")
    // }
}
