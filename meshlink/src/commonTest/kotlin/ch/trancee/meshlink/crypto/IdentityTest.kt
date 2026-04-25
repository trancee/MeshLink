package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [Identity] and [RotationAnnouncement].
 *
 * Branch-coverage note: [Identity.loadOrGenerate] has a 4-condition `&&` whose short-circuit
 * branches are covered by the five storage-state scenarios below (empty, all present, and three
 * "one key missing" variants that exercise each individual null-check).
 */
class IdentityTest {

    private val crypto: CryptoProvider = createCryptoProvider()

    // ── (a) Fresh generation ─────────────────────────────────────────────────

    @Test
    fun loadOrGenerateWithEmptyStorageCreatesNewIdentityWithCorrectKeySizes() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)

        assertEquals(64, identity.edKeyPair.privateKey.size, "Ed25519 private key must be 64 bytes")
        assertEquals(32, identity.edKeyPair.publicKey.size, "Ed25519 public key must be 32 bytes")
        assertEquals(32, identity.dhKeyPair.privateKey.size, "X25519 private key must be 32 bytes")
        assertEquals(32, identity.dhKeyPair.publicKey.size, "X25519 public key must be 32 bytes")
        assertEquals(12, identity.keyHash.size, "Key Hash must be 12 bytes")
    }

    // ── (b) Load from storage ────────────────────────────────────────────────

    @Test
    fun loadOrGenerateWithAllKeysInStorageLoadsExistingIdentity() {
        val storage = InMemorySecureStorage()

        // Generate and persist a fresh identity
        val original = Identity.loadOrGenerate(crypto, storage)

        // Load it again from the same storage
        val loaded = Identity.loadOrGenerate(crypto, storage)

        assertContentEquals(original.edKeyPair.publicKey, loaded.edKeyPair.publicKey)
        assertContentEquals(original.edKeyPair.privateKey, loaded.edKeyPair.privateKey)
        assertContentEquals(original.dhKeyPair.publicKey, loaded.dhKeyPair.publicKey)
        assertContentEquals(original.dhKeyPair.privateKey, loaded.dhKeyPair.privateKey)
        assertContentEquals(original.keyHash, loaded.keyHash)
    }

    // ── (c) Key Hash correctness ─────────────────────────────────────────────

    @Test
    fun keyHashIsSha256OfEdPubConcatDhPubTruncatedTo12Bytes() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)

        val expected =
            crypto.sha256(identity.edKeyPair.publicKey + identity.dhKeyPair.publicKey).copyOf(12)

        assertContentEquals(expected, identity.keyHash)
    }

    // ── (d) rotateKeys — no prior nonce ──────────────────────────────────────

    @Test
    fun rotateKeysReturnsAnnouncementVerifiableWithOldEdPublicKey() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val oldEdPublicKey = identity.edKeyPair.publicKey.copyOf()

        val announcement = identity.rotateKeys(crypto, storage)

        assertTrue(
            RotationAnnouncement.verify(crypto, oldEdPublicKey, announcement),
            "Announcement must verify against the OLD Ed25519 public key",
        )
        assertEquals(32, announcement.newEdPublicKey.size)
        assertEquals(32, announcement.newDhPublicKey.size)
        assertEquals(64, announcement.signature.size)
        assertEquals(1UL, announcement.rotationNonce, "First rotation nonce must be 1")
    }

    // ── (e) rotateKeys — increments nonce ────────────────────────────────────

    @Test
    fun rotateKeysIncrementsNonceOnSubsequentRotations() {
        val storage = InMemorySecureStorage()
        val identity1 = Identity.loadOrGenerate(crypto, storage)

        // First rotation: nonce absent in storage → nonce becomes 1
        val announcement1 = identity1.rotateKeys(crypto, storage)
        assertEquals(1UL, announcement1.rotationNonce)

        // Load new identity (storage now has new keys and nonce=1)
        val identity2 = Identity.loadOrGenerate(crypto, storage)
        val oldEdPublicKey2 = identity2.edKeyPair.publicKey.copyOf()

        // Second rotation: nonce present (=1) → decoded and incremented to 2
        val announcement2 = identity2.rotateKeys(crypto, storage)
        assertEquals(2UL, announcement2.rotationNonce)

        // Announcement2 must still verify against identity2's old public key
        assertTrue(RotationAnnouncement.verify(crypto, oldEdPublicKey2, announcement2))
    }

    // ── (f) After rotation, new identity loaded from storage ─────────────────

    @Test
    fun afterRotationLoadOrGenerateLoadsNewKeys() {
        val storage = InMemorySecureStorage()
        val original = Identity.loadOrGenerate(crypto, storage)
        val announcement = original.rotateKeys(crypto, storage)

        val rotated = Identity.loadOrGenerate(crypto, storage)

        assertContentEquals(announcement.newEdPublicKey, rotated.edKeyPair.publicKey)
        assertContentEquals(announcement.newDhPublicKey, rotated.dhKeyPair.publicKey)
        assertFalse(
            original.edKeyPair.publicKey.contentEquals(rotated.edKeyPair.publicKey),
            "New identity should have different keys after rotation",
        )
    }

    // ── (g1) Partial storage: only ed25519 private key present ───────────────
    //   Covers the branch where edPriv != null but edPub == null (2nd && false).

    @Test
    fun loadOrGenerateWithOnlyEdPrivKeyRegeneratesIdentity() {
        val storage = InMemorySecureStorage()
        // Populate only ed25519 private key — edPub, dhPriv, dhPub absent
        val edKeyPair = crypto.generateEd25519KeyPair()
        storage.put("meshlink.identity.ed25519.private", edKeyPair.privateKey)

        val identity = Identity.loadOrGenerate(crypto, storage)

        // Should generate fresh, not use the half-populated storage
        assertEquals(12, identity.keyHash.size)
        assertEquals(64, identity.edKeyPair.privateKey.size)
        assertEquals(32, identity.dhKeyPair.privateKey.size)
    }

    // ── (g2) Partial storage: ed25519 private + public, x25519 private absent ─
    //   Covers the branch where edPriv + edPub != null but dhPriv == null (3rd && false).

    @Test
    fun loadOrGenerateWithMissingDhPrivKeyRegeneratesIdentity() {
        val storage = InMemorySecureStorage()
        val edKeyPair = crypto.generateEd25519KeyPair()
        val dhKeyPair = crypto.generateX25519KeyPair()
        storage.put("meshlink.identity.ed25519.private", edKeyPair.privateKey)
        storage.put("meshlink.identity.ed25519.public", edKeyPair.publicKey)
        // dhPriv absent, dhPub absent

        val identity = Identity.loadOrGenerate(crypto, storage)
        assertEquals(12, identity.keyHash.size)
    }

    // ── (g3) Partial storage: all except x25519 public key ───────────────────
    //   Covers the branch where edPriv + edPub + dhPriv != null but dhPub == null (4th && false).

    @Test
    fun loadOrGenerateWithMissingDhPubKeyRegeneratesIdentity() {
        val storage = InMemorySecureStorage()
        val edKeyPair = crypto.generateEd25519KeyPair()
        val dhKeyPair = crypto.generateX25519KeyPair()
        storage.put("meshlink.identity.ed25519.private", edKeyPair.privateKey)
        storage.put("meshlink.identity.ed25519.public", edKeyPair.publicKey)
        storage.put("meshlink.identity.x25519.private", dhKeyPair.privateKey)
        // dhPub absent

        val identity = Identity.loadOrGenerate(crypto, storage)
        assertEquals(12, identity.keyHash.size)
    }

    // ── (h) RotationAnnouncement.verify rejects tampered signature ────────────

    @Test
    fun rotationAnnouncementVerifyRejectsTamperedSignature() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val oldEdPublicKey = identity.edKeyPair.publicKey.copyOf()
        val announcement = identity.rotateKeys(crypto, storage)

        // Flip a byte in the signature
        val tamperedSig = announcement.signature.copyOf()
        tamperedSig[0] = (tamperedSig[0].toInt() xor 0x01).toByte()
        val tampered =
            RotationAnnouncement(
                announcement.newEdPublicKey,
                announcement.newDhPublicKey,
                announcement.rotationNonce,
                tamperedSig,
            )

        assertFalse(
            RotationAnnouncement.verify(crypto, oldEdPublicKey, tampered),
            "Tampered signature must not verify",
        )
    }

    // ── (i) RotationAnnouncement.verify rejects wrong old public key ──────────

    @Test
    fun rotationAnnouncementVerifyRejectsWrongOldPublicKey() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val announcement = identity.rotateKeys(crypto, storage)

        // Use a different (unrelated) public key for verification
        val wrongKeyPair = crypto.generateEd25519KeyPair()
        assertFalse(
            RotationAnnouncement.verify(crypto, wrongKeyPair.publicKey, announcement),
            "Wrong old public key must not verify",
        )
    }

    // ── (j) Identity equals / hashCode based on keyHash ──────────────────────

    @Test
    fun identityEqualsSameReference() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        // Same reference must equal itself
        assertEquals(identity, identity)
    }

    @Test
    fun identityEqualsIdenticalKeyHashContent() {
        val storage = InMemorySecureStorage()
        val identity1 = Identity.loadOrGenerate(crypto, storage)
        // Load the same keys again — produces a separate object with identical keyHash
        val identity2 = Identity.loadOrGenerate(crypto, storage)
        assertEquals(identity1, identity2)
    }

    @Test
    fun identityNotEqualDifferentKeyHash() {
        val storage1 = InMemorySecureStorage()
        val storage2 = InMemorySecureStorage()
        val identity1 = Identity.loadOrGenerate(crypto, storage1)
        val identity2 = Identity.loadOrGenerate(crypto, storage2)
        // Two independently generated identities must differ (overwhelmingly probable)
        assertNotEquals(identity1, identity2)
    }

    @Test
    fun identityNotEqualNonIdentityObject() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        assertFalse(identity.equals("not an identity"))
    }

    @Test
    fun identityHashCodeConsistentWithEquals() {
        val storage = InMemorySecureStorage()
        val identity1 = Identity.loadOrGenerate(crypto, storage)
        val identity2 = Identity.loadOrGenerate(crypto, storage)
        assertEquals(identity1.hashCode(), identity2.hashCode())
    }

    // ── Nonce encode/decode round-trip ────────────────────────────────────────

    @Test
    fun nonceEncodeDecodeRoundTrip() {
        val values = listOf(0UL, 1UL, 255UL, 256UL, ULong.MAX_VALUE)
        for (v in values) {
            val bytes = Identity.encodeULong(v)
            assertEquals(8, bytes.size, "Encoded nonce must be 8 bytes")
            assertEquals(v, Identity.decodeULong(bytes), "Round-trip failed for $v")
        }
    }

    @Test
    fun nonceEncodingIsLittleEndian() {
        // 1UL should be [0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
        val bytes = Identity.encodeULong(1UL)
        assertEquals(0x01.toByte(), bytes[0], "LSB must be first in little-endian encoding")
        assertEquals(0x00.toByte(), bytes[7], "MSB must be last in little-endian encoding")
    }
}
