package io.meshlink.wire

import io.meshlink.crypto.PureKotlinCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class RotationAnnouncementTest {

    private val crypto = PureKotlinCryptoProvider()

    private fun createSignedMessage(
        timestampMillis: ULong = 1_700_000_000_000u,
    ): Pair<RotationAnnouncement.RotationMessage, ByteArray> {
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()
        val oldX = crypto.generateX25519KeyPair()
        val newX = crypto.generateX25519KeyPair()

        val payload = RotationAnnouncement.buildSignablePayload(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            timestampMillis,
        )
        val signature = crypto.sign(oldEd.privateKey, payload)

        val encoded = RotationAnnouncement.encode(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            timestampMillis, signature,
        )

        val message = RotationAnnouncement.RotationMessage(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            timestampMillis, signature,
        )
        return message to encoded
    }

    @Test
    fun roundTripEncodeDecodePreservesAllFields() {
        val (original, encoded) = createSignedMessage()
        val decoded = RotationAnnouncement.decode(encoded)

        assertContentEquals(original.oldX25519Key, decoded.oldX25519Key)
        assertContentEquals(original.newX25519Key, decoded.newX25519Key)
        assertContentEquals(original.oldEd25519Key, decoded.oldEd25519Key)
        assertContentEquals(original.newEd25519Key, decoded.newEd25519Key)
        assertEquals(original.timestampMillis, decoded.timestampMillis)
        assertContentEquals(original.signature, decoded.signature)
    }

    @Test
    fun signatureVerificationPassesViaSecurityEngine() {
        val (_, encoded) = createSignedMessage()
        val decoded = RotationAnnouncement.decode(encoded)

        val signablePayload = RotationAnnouncement.buildSignablePayload(
            decoded.oldX25519Key,
            decoded.newX25519Key,
            decoded.oldEd25519Key,
            decoded.newEd25519Key,
            decoded.timestampMillis,
        )
        assertTrue(crypto.verify(decoded.oldEd25519Key, signablePayload, decoded.signature))
    }

    @Test
    fun signatureVerificationFailsWithWrongKey() {
        val (_, encoded) = createSignedMessage()
        val decoded = RotationAnnouncement.decode(encoded)

        val wrongKey = crypto.generateEd25519KeyPair().publicKey
        val signablePayload = RotationAnnouncement.buildSignablePayload(
            decoded.oldX25519Key,
            decoded.newX25519Key,
            wrongKey,
            decoded.newEd25519Key,
            decoded.timestampMillis,
        )
        assertFalse(crypto.verify(wrongKey, signablePayload, decoded.signature))
    }

    @Test
    fun signatureVerificationFailsWithTamperedNewKey() {
        val (_, encoded) = createSignedMessage()
        val decoded = RotationAnnouncement.decode(encoded)

        val tamperedNewEd = crypto.generateEd25519KeyPair().publicKey
        val signablePayload = RotationAnnouncement.buildSignablePayload(
            decoded.oldX25519Key,
            decoded.newX25519Key,
            decoded.oldEd25519Key,
            tamperedNewEd,
            decoded.timestampMillis,
        )
        assertFalse(crypto.verify(decoded.oldEd25519Key, signablePayload, decoded.signature))
    }

    @Test
    fun signatureVerificationFailsWithTamperedTimestamp() {
        val (_, encoded) = createSignedMessage()
        val decoded = RotationAnnouncement.decode(encoded)

        val signablePayload = RotationAnnouncement.buildSignablePayload(
            decoded.oldX25519Key,
            decoded.newX25519Key,
            decoded.oldEd25519Key,
            decoded.newEd25519Key,
            decoded.timestampMillis + 1u,
        )
        assertFalse(crypto.verify(decoded.oldEd25519Key, signablePayload, decoded.signature))
    }

    @Test
    fun decodeThrowsOnShortData() {
        val shortData = ByteArray(200) // need 201
        shortData[0] = RotationAnnouncement.TYPE_ROTATION
        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.decode(shortData)
        }
    }

    @Test
    fun typeRotationByteIsCorrectAtPosition0() {
        val (_, encoded) = createSignedMessage()
        assertEquals(0x02.toByte(), encoded[0])
        assertEquals(RotationAnnouncement.TYPE_ROTATION, encoded[0])
    }

    @Test
    fun timestampRoundTripsCorrectlyForLargeValues() {
        val maxTimestamp = ULong.MAX_VALUE
        val (_, encoded) = createSignedMessage(timestampMillis = maxTimestamp)
        val decoded = RotationAnnouncement.decode(encoded)
        assertEquals(maxTimestamp, decoded.timestampMillis)

        val highBitTimestamp: ULong = 0x8000_0000_0000_0000u
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()
        val oldX = crypto.generateX25519KeyPair()
        val newX = crypto.generateX25519KeyPair()
        val payload = RotationAnnouncement.buildSignablePayload(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            highBitTimestamp,
        )
        val sig = crypto.sign(oldEd.privateKey, payload)
        val enc = RotationAnnouncement.encode(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            highBitTimestamp, sig,
        )
        val dec = RotationAnnouncement.decode(enc)
        assertEquals(highBitTimestamp, dec.timestampMillis)
    }

    @Test
    fun buildSignablePayloadProducesCorrectLength() {
        val oldX = crypto.generateX25519KeyPair()
        val newX = crypto.generateX25519KeyPair()
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()

        val payload = RotationAnnouncement.buildSignablePayload(
            oldX.publicKey, newX.publicKey,
            oldEd.publicKey, newEd.publicKey,
            1_700_000_000_000u,
        )
        assertEquals(136, payload.size)
    }

    @Test
    fun multipleRotationsProduceDifferentSignatures() {
        val (msg1, _) = createSignedMessage(timestampMillis = 1_000u)
        val (msg2, _) = createSignedMessage(timestampMillis = 2_000u)
        assertFalse(msg1.signature.contentEquals(msg2.signature))
    }

    @Test
    fun keySizesAreValidated() {
        val valid32 = ByteArray(32)
        val tooShort = ByteArray(31)
        val sig64 = ByteArray(64)

        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.encode(tooShort, valid32, valid32, valid32, 0u, sig64)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.encode(valid32, tooShort, valid32, valid32, 0u, sig64)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.encode(valid32, valid32, tooShort, valid32, 0u, sig64)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.encode(valid32, valid32, valid32, tooShort, 0u, sig64)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationAnnouncement.buildSignablePayload(tooShort, valid32, valid32, valid32, 0u)
        }
    }

    @Test
    fun allFourKeysAreIndependentlyPreserved() {
        val oldX = crypto.generateX25519KeyPair().publicKey
        val newX = crypto.generateX25519KeyPair().publicKey
        val oldEd = crypto.generateEd25519KeyPair().publicKey
        val newEd = crypto.generateEd25519KeyPair().publicKey
        val sig = ByteArray(64) { it.toByte() }

        val encoded = RotationAnnouncement.encode(oldX, newX, oldEd, newEd, 42u, sig)
        val decoded = RotationAnnouncement.decode(encoded)

        assertContentEquals(oldX, decoded.oldX25519Key)
        assertContentEquals(newX, decoded.newX25519Key)
        assertContentEquals(oldEd, decoded.oldEd25519Key)
        assertContentEquals(newEd, decoded.newEd25519Key)

        // Ensure keys are not accidentally swapped
        assertFalse(decoded.oldX25519Key.contentEquals(decoded.newX25519Key))
        assertFalse(decoded.oldEd25519Key.contentEquals(decoded.newEd25519Key))
        assertFalse(decoded.oldX25519Key.contentEquals(decoded.oldEd25519Key))
    }

    @Test
    fun encodedSizeIsExactly201Bytes() {
        val (_, encoded) = createSignedMessage()
        assertEquals(RotationAnnouncement.SIZE, encoded.size)
        assertEquals(201, encoded.size)
    }

    @Test
    fun wireCodecTypeRotationConstantMatches() {
        assertEquals(WireCodec.TYPE_ROTATION, RotationAnnouncement.TYPE_ROTATION)
    }
}
