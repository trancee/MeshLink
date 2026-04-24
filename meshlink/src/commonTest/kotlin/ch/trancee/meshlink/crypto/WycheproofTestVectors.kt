package ch.trancee.meshlink.crypto

/**
 * Decodes a lowercase hex string (even length) to a [ByteArray].
 *
 * Test-only utility; not measured by Kover.
 */
internal fun String.hexToByteArray(): ByteArray {
    if (length % 2 != 0) throw IllegalArgumentException("Hex string must have even length")
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Known-answer test vectors derived from Wycheproof and IETF RFCs.
 *
 * Sources:
 * - Ed25519: RFC 8032 §A (test vectors 1, 2, 3)
 * - X25519: RFC 7748 §6.1
 * - ChaCha20-Poly1305: RFC 8439 §2.8.2
 * - SHA-256: FIPS 180-4 Appendix B
 * - HKDF-SHA-256: RFC 5869 §A (test cases 1, 2, 3)
 */
object WycheproofTestVectors {

    // ── Ed25519 (RFC 8032 §A) ─────────────────────────────────────────────────

    /** Ed25519 sign/verify test vector. */
    data class Ed25519Vector(
        /** 64-byte private key: seed (32) || public key (32) — libsodium convention. */
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val message: ByteArray,
        val signature: ByteArray,
        val valid: Boolean,
    )

    // RFC 8032 Test Vector 1 — empty message
    private val seed1 =
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae3d55".hexToByteArray()
    private val pub1 =
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray()

    val ed25519Vector1 =
        Ed25519Vector(
            privateKey = seed1 + pub1,
            publicKey = pub1,
            message = byteArrayOf(),
            signature =
                ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901" +
                        "555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
                    .hexToByteArray(),
            valid = true,
        )

    // RFC 8032 Test Vector 2 — 1-byte message
    private val seed2 =
        "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4d0bd6ef".hexToByteArray()
    private val pub2 =
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".hexToByteArray()

    val ed25519Vector2 =
        Ed25519Vector(
            privateKey = seed2 + pub2,
            publicKey = pub2,
            message = "72".hexToByteArray(),
            signature =
                ("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69" +
                        "da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
                    .hexToByteArray(),
            valid = true,
        )

    // RFC 8032 Test Vector 3 — 2-byte message
    private val seed3 =
        "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7".hexToByteArray()
    private val pub3 =
        "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025".hexToByteArray()

    val ed25519Vector3 =
        Ed25519Vector(
            privateKey = seed3 + pub3,
            publicKey = pub3,
            message = "af82".hexToByteArray(),
            signature =
                ("6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3" +
                        "ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a")
                    .hexToByteArray(),
            valid = true,
        )

    val ed25519Vectors = listOf(ed25519Vector1, ed25519Vector2, ed25519Vector3)

    // ── X25519 (RFC 7748 §6.1) ────────────────────────────────────────────────

    /** X25519 Diffie-Hellman test vector. */
    data class X25519Vector(
        val alicePrivate: ByteArray,
        val alicePublic: ByteArray,
        val bobPrivate: ByteArray,
        val bobPublic: ByteArray,
        val sharedSecret: ByteArray,
    )

    /** RFC 7748 §6.1 — Alice/Bob key agreement. */
    val x25519Vector1 =
        X25519Vector(
            alicePrivate =
                "77076d0a7318a57d3c16c17251b26645c6c2f6c7bf88afec73924c4b9b8feafb".hexToByteArray(),
            alicePublic =
                "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hexToByteArray(),
            bobPrivate =
                "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToByteArray(),
            bobPublic =
                "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToByteArray(),
            sharedSecret =
                "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToByteArray(),
        )

    // ── ChaCha20-Poly1305 IETF AEAD (RFC 8439 §2.8.2) ────────────────────────

    /** ChaCha20-Poly1305 AEAD test vector. [ciphertext] includes the 16-byte Poly1305 tag. */
    data class AeadVector(
        val key: ByteArray,
        val nonce: ByteArray,
        val plaintext: ByteArray,
        val aad: ByteArray,
        /** Ciphertext bytes concatenated with the 16-byte Poly1305 authentication tag. */
        val ciphertext: ByteArray,
    )

    /**
     * RFC 8439 §2.8.2 — "Ladies and Gentlemen…" sunscreen message. Plaintext is 114 bytes;
     * ciphertext+tag is 130 bytes.
     */
    val aeadVector1 =
        AeadVector(
            key =
                "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToByteArray(),
            nonce = "070000004041424344454647".hexToByteArray(),
            plaintext =
                ("Ladies and Gentlemen of the class of '99: If I could offer you" +
                        " only one tip for the future, sunscreen would be it.")
                    .encodeToByteArray(),
            aad = "50515253c0c1c2c3c4c5c6c7".hexToByteArray(),
            ciphertext =
                ("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                        "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                        "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                        "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd060" +
                        "0691")
                    .hexToByteArray(),
        )

    // ── SHA-256 (FIPS 180-4) ──────────────────────────────────────────────────

    /** SHA-256 known-answer test vector. */
    data class Sha256Vector(val input: ByteArray, val digest: ByteArray)

    /** SHA-256("") — empty input. */
    val sha256Vector1 =
        Sha256Vector(
            input = byteArrayOf(),
            digest =
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".hexToByteArray(),
        )

    /** SHA-256("abc") — FIPS 180-4 §B.1. */
    val sha256Vector2 =
        Sha256Vector(
            input = "616263".hexToByteArray(),
            digest =
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray(),
        )

    /** SHA-256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") — FIPS 180-4 §B.2. */
    val sha256Vector3 =
        Sha256Vector(
            input =
                ("6162636462636465636465666465666765666768666768696768696a68696a6b" +
                        "696a6b6c6a6b6c6d6b6c6d6e6c6d6e6f6d6e6f706e6f7071")
                    .hexToByteArray(),
            digest =
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1".hexToByteArray(),
        )

    val sha256Vectors = listOf(sha256Vector1, sha256Vector2, sha256Vector3)

    // ── HKDF-SHA-256 (RFC 5869 §A) ────────────────────────────────────────────

    /** HKDF-SHA-256 known-answer test vector. */
    data class HkdfVector(
        val ikm: ByteArray,
        val salt: ByteArray,
        val info: ByteArray,
        val length: Int,
        val okm: ByteArray,
    )

    /** RFC 5869 §A.1 — Test Case 1. */
    val hkdfVector1 =
        HkdfVector(
            ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray(),
            salt = "000102030405060708090a0b0c".hexToByteArray(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray(),
            length = 42,
            okm =
                ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5b" +
                        "f34007208d5b887185865")
                    .hexToByteArray(),
        )

    /**
     * RFC 5869 §A.2 — Test Case 2. IKM 0x00–0x4f, salt 0x60–0xaf, info 0xb0–0xff (80 bytes each).
     * Output length 82 bytes.
     */
    val hkdfVector2 =
        HkdfVector(
            ikm =
                ("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                        "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                        "404142434445464748494a4b4c4d4e4f")
                    .hexToByteArray(),
            salt =
                ("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                        "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                        "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
                    .hexToByteArray(),
            info =
                ("b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
                    .hexToByteArray(),
            length = 82,
            okm =
                ("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                        "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                        "cc30c58179ec3e87c14c01d5c1f3434f1d87")
                    .hexToByteArray(),
        )

    /**
     * RFC 5869 §A.3 — Test Case 3. Empty salt (treated as 32 zero bytes per RFC 5869 §2.2) and
     * empty info. Same IKM as Test Case 1; output length 42 bytes.
     */
    val hkdfVector3 =
        HkdfVector(
            ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray(),
            salt = byteArrayOf(),
            info = byteArrayOf(),
            length = 42,
            okm =
                ("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                        "9d201395faa4b61a96c8")
                    .hexToByteArray(),
        )

    val hkdfVectors = listOf(hkdfVector1, hkdfVector2, hkdfVector3)
}
