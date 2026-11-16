package ch.trancee.meshlink.vectors

import ch.trancee.meshlink.crypto.sodium.FixedClock
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.crypto.sodium.assertFailed
import ch.trancee.meshlink.crypto.sodium.hex
import ch.trancee.meshlink.crypto.sodium.ok
import ch.trancee.meshlink.internal.CertificateCheck
import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.StaticKeys
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private val crypto: CryptoProvider = SodiumCryptoProvider
private val derivations = Derivations(crypto)

/** A `derivations.json` handler: [compute] of the test's `inputs` must equal its `output`. */
private fun derivation(compute: (Json) -> ByteArray): Handler =
    Handler("output") { case -> assertEquals(case.test["output"].string, compute(case.test["inputs"]).hex()) }

internal val epoch: Handler =
    derivation { inputs ->
        val epoch = Derivations.epoch(inputs["unix_seconds"].long)
        ByteArray(UInt.SIZE_BYTES) { (epoch shr (Byte.SIZE_BITS * (UInt.SIZE_BYTES - 1 - it))).toByte() }
    }

internal val nodeId: Handler = derivation { derivations.nodeId(it["ed25519_key"].string.hex()) }

internal val meshIdDigest: Handler =
    derivation { inputs ->
        val profile = WireProfile.entries.single { it.code == inputs["profile"].int }
        derivations.meshIdDigest(inputs["mesh_id_utf8"].string.hex(), profile)
    }

internal val meshTag: Handler =
    derivation { derivations.meshTag(it["tag_key"].string.hex(), it["epoch"].uInt) }

internal val unsecuredTag: Handler =
    derivation { derivations.unsecuredTag(it["node_id"].string.hex(), it["epoch"].uInt) }

internal val tombstoneCommitment: Handler = derivation { derivations.tombstoneCommitment(it["k"].string.hex()) }

internal val certificateSigningInput: Handler =
    derivation { IdentityCertificate.signingInput(it["certificate_without_signature"].string.hex()) }

/**
 * `certificate-signatures.json` (tier 2): validation at `now` must give the test's result or error.
 * A test with the signer's `ed25519_seed` must reproduce the signature byte for byte, and a valid
 * one must be reissued byte for byte from its keys by a clock at `not_before`.
 */
internal val certificateVerify: Handler =
    Handler("certificate") { case ->
        val bytes = case.hex("certificate")
        val profile =
            WireProfile.entries.single {
                it.code ==
                    case.group["profile"]
                        .string
                        .removePrefix("0x")
                        .toInt(HEX)
            }
        val clock = FixedClock.atSeconds(case.test["now"].long)
        val check = IdentityCertificate.validate(crypto, bytes, profile, case.hex("node_id"), clock)
        val body = bytes.copyOf(maxOf(0, bytes.size - SIGNATURE_LENGTH))
        if (case.test.has("signing_input")) {
            val signingInput = IdentityCertificate.signingInput(body).hex()
            assertEquals(case.test["signing_input"].string, signingInput, "signing_input")
        }
        if (case.test.has("ed25519_seed")) {
            val signature = crypto.ed25519Sign(case.hex("ed25519_seed"), IdentityCertificate.signingInput(body))
            assertEquals(bytes.copyOfRange(body.size, bytes.size).hex(), signature.hex(), "signature")
        }
        if (case.result == "valid") {
            val certificate = assertIs<CertificateCheck.Valid>(check).certificate
            val reissued =
                IdentityCertificate.issue(
                    crypto,
                    FixedClock.atSeconds(certificate.notBefore.toLong()),
                    crypto.ed25519KeyPair(case.hex("ed25519_seed")),
                    StaticKeys(certificate.x25519Key, certificate.xWingKey),
                    (certificate.notAfter - certificate.notBefore).toLong().seconds,
                )
            assertContentEquals(bytes, reissued.encode(), "reissued")
        } else {
            assertEquals(case.test["error"].string, assertIs<CertificateCheck.Invalid>(check).code)
        }
    }

/** Wycheproof `XdhComp`: an all-zero shared secret is refused as a low-order public key, never returned. */
internal val x25519: Handler =
    Handler("shared") { case ->
        val result = crypto.x25519(case.hex("private"), case.hex("public"))
        val shared = case.hex("shared")
        if (shared.all { it == 0.toByte() }) {
            result.assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
        } else {
            assertEquals(shared.hex(), result.ok().hex())
        }
    }

/** Wycheproof `EddsaVerify`; an `acceptable` signature may verify or not, but must not throw. */
internal val ed25519: Handler =
    Handler("sig") { case ->
        val publicKey = case.group["publicKey"]["pk"].string.hex()
        val verified = crypto.ed25519Verify(publicKey, case.hex("msg"), case.hex("sig"))
        when (case.result) {
            "valid" -> assertTrue(verified, "signature refused")
            "invalid" -> assertFalse(verified, "signature verified")
        }
    }

/** Wycheproof `AeadTest`; a nonce that isn't 12 bytes is refused before use. */
internal val chaCha20Poly1305: Handler =
    Handler("ct") { case ->
        val key = case.hex("key")
        val nonce = case.hex("iv")
        val aad = case.hex("aad")
        val sealed = case.hex("ct") + case.hex("tag")
        when {
            case.flagged("InvalidNonceSize") -> {
                assertFailsWith<IllegalArgumentException> { crypto.chaCha20Poly1305Open(key, nonce, sealed, aad) }
            }

            case.result == "valid" -> {
                assertEquals(sealed.hex(), crypto.chaCha20Poly1305Seal(key, nonce, case.hex("msg"), aad).hex(), "seal")
                assertEquals(case.test["msg"].string, crypto.chaCha20Poly1305Open(key, nonce, sealed, aad).ok().hex())
            }

            else -> {
                crypto.chaCha20Poly1305Open(key, nonce, sealed, aad).assertFailed(CryptoFailure.AUTHENTICATION_FAILED)
            }
        }
    }

/** Wycheproof `HkdfTest`; an output above 255 hash lengths is refused. */
internal val hkdfSha256: Handler =
    Handler("okm") { case ->
        val prk = crypto.hkdfSha256Extract(case.hex("salt"), case.hex("ikm"))
        val size = case.test["size"].int
        if (case.result == "valid") {
            assertEquals(case.test["okm"].string, crypto.hkdfSha256Expand(prk, case.hex("info"), size).hex())
        } else {
            assertFailsWith<IllegalArgumentException> { crypto.hkdfSha256Expand(prk, case.hex("info"), size) }
        }
    }

/** Wycheproof `MLKEMTest`: the key pair of `seed` decapsulates `c` to `K`; an invalid test has a wrong length. */
internal val mlKem768: Handler =
    Handler("K") { case ->
        if (case.result == "valid") {
            val keyPair = crypto.mlKem768KeyPair(case.hex("seed"))
            if (case.test.has("ek")) assertEquals(case.test["ek"].string, keyPair.publicKey.hex(), "ek")
            assertEquals(case.test["K"].string, crypto.mlKem768Decapsulate(keyPair.secretKey, case.hex("c")).hex())
        } else {
            assertFailsWith<IllegalArgumentException> {
                crypto.mlKem768Decapsulate(crypto.mlKem768KeyPair(case.hex("seed")).secretKey, case.hex("c"))
            }
        }
    }

/** Wycheproof `MLKEMEncapsTest`; an invalid `ek` of the right length fails the FIPS 203 modulus check. */
internal val mlKem768Encapsulate: Handler =
    Handler("K") { case ->
        val ek = case.hex("ek")
        when {
            case.result == "valid" -> {
                val encapsulation = crypto.mlKem768Encapsulate(ek, case.hex("m")).ok()
                assertEquals(case.test["c"].string, encapsulation.ciphertext.hex(), "c")
                assertEquals(case.test["K"].string, encapsulation.sharedSecret.hex(), "K")
            }

            ek.size == ML_KEM_PUBLIC_KEY -> {
                crypto.mlKem768Encapsulate(ek, case.hex("m")).assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
            }

            else -> {
                assertFailsWith<IllegalArgumentException> { crypto.mlKem768Encapsulate(ek, case.hex("m")) }
            }
        }
    }

/** Wycheproof `MLKEMKeyGen`: the 64-byte seed gives `ek` and the 2,400-byte `dk`. */
internal val mlKem768KeyGen: Handler =
    Handler("ek") { case ->
        val keyPair = crypto.mlKem768KeyPair(case.hex("seed"))
        assertEquals(case.test["ek"].string, keyPair.publicKey.hex(), "ek")
        assertEquals(case.test["dk"].string, keyPair.secretKey.hex(), "dk")
    }

/** Wycheproof `MLKEMDecapsValidationTest`: decapsulation with a given 2,400-byte `dk`. */
internal val mlKem768Decapsulate: Handler =
    Handler("K") { case ->
        val dk = case.hex("dk")
        val c = case.hex("c")
        if (case.result == "valid") {
            assertEquals(case.test["K"].string, crypto.mlKem768Decapsulate(dk, c).hex())
        } else {
            assertFailsWith<IllegalArgumentException> { crypto.mlKem768Decapsulate(dk, c) }
        }
    }

/**
 * draft-ietf-hpke-pq-05 Appendix A, X-Wing (`0x647a`): the raw KEM's fields. `skRm` is the X-Wing
 * seed, `ikmE` the encapsulation seed. The HPKE key schedule fields belong to ticket 45.
 */
internal val xWing: Handler =
    Handler("shared_secret") { case ->
        val keyPair = crypto.xWingKeyPair(case.hex("skRm"))
        assertEquals(case.test["pkRm"].string, keyPair.publicKey.hex(), "pkRm")
        val encapsulation = crypto.xWingEncapsulate(keyPair.publicKey, case.hex("ikmE")).ok()
        assertEquals(case.test["enc"].string, encapsulation.ciphertext.hex(), "enc")
        assertEquals(case.test["shared_secret"].string, encapsulation.sharedSecret.hex(), "shared_secret")
        val decapsulated = crypto.xWingDecapsulate(keyPair.secretKey, case.hex("enc"))
        if (decapsulated !is CryptoResult.Ok) fail("decapsulation failed: $decapsulated")
        assertEquals(case.test["shared_secret"].string, decapsulated.value.hex(), "decapsulated")
    }

private const val ML_KEM_PUBLIC_KEY = 1184

private const val SIGNATURE_LENGTH = 64
private const val HEX = 16

/**
 * cacophony: each role replays the vector with its prologue, psk, static and ephemeral keys. Every
 * message, handshake and transport alike, must match the vector's ciphertext and decrypt to its
 * payload; both sides must reach its handshake hash.
 */
internal val noise: Handler =
    Handler("handshake_hash") { case ->
        val pattern = NoisePattern.entries.single { it.protocolName == case.test["protocol_name"].string }
        val (initiator, responder) =
            listOf(NoiseRole.INITIATOR to "init", NoiseRole.RESPONDER to "resp").map { (role, side) ->
                val psk =
                    if (case.test.has("${side}_psks")) {
                        case.test["${side}_psks"]
                            .list
                            .single()
                            .string
                            .hex()
                    } else {
                        null
                    }
                val keys =
                    NoiseKeys(
                        crypto.x25519KeyPair(case.hex("${side}_static")),
                        psk,
                        crypto.x25519KeyPair(case.hex("${side}_ephemeral")),
                    )
                NoiseHandshake(crypto, pattern, role, case.hex("${side}_prologue"), keys)
            }
        val messages = case.test["messages"].list
        messages.take(HANDSHAKE_MESSAGES).forEachIndexed { index, message ->
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            val payload = message["payload"].string
            val ciphertext = writer.writeMessage(payload.hex()).value().hex()
            assertEquals(message["ciphertext"].string, ciphertext, "message $index")
            assertEquals(payload, reader.readMessage(message["ciphertext"].string.hex()).value().hex())
        }
        val (sending, receiving) = initiator.split().value() to responder.split().value()
        assertEquals(case.test["handshake_hash"].string, sending.handshakeHash.hex())
        assertEquals(case.test["handshake_hash"].string, receiving.handshakeHash.hex())
        messages.drop(HANDSHAKE_MESSAGES).forEachIndexed { offset, message ->
            val (writer, reader) = if (offset % 2 == 1) sending to receiving else receiving to sending
            val payload = message["payload"].string
            val ciphertext = writer.encrypt(payload.hex()).value().hex()
            assertEquals(message["ciphertext"].string, ciphertext, "message ${offset + HANDSHAKE_MESSAGES}")
            assertEquals(payload, reader.decrypt(ciphertext.hex()).value().hex())
        }
    }

/**
 * `handshake-hfs.json` (tier 2): each side replays its handshake from its keys and KEM input. A
 * writer's message must equal the delivered one except at `tampered_step`; the reader of
 * `rejected_step` must fail and every earlier read must succeed. A valid run must reach the
 * handshake hash on both sides and reproduce each transport message.
 */
internal val noiseHandshake: Handler =
    Handler("handshake_hash") { case ->
        val pattern = NoisePattern.entries.single { it.protocolName == case.group["protocol"].string }
        val (initiator, responder) =
            listOf(NoiseRole.INITIATOR to "initiator", NoiseRole.RESPONDER to "responder").map { (role, name) ->
                val side = case.test[name]
                val optional = { field: String -> if (side.has(field)) side[field].string.hex() else null }
                val keys =
                    NoiseKeys(
                        crypto.x25519KeyPair(side["static_private"].string.hex()),
                        optional("psk"),
                        crypto.x25519KeyPair(side["ephemeral_private"].string.hex()),
                        optional("kem_keygen_seed")?.let(crypto::mlKem768KeyPair),
                        optional("kem_encaps_seed"),
                    )
                NoiseHandshake(crypto, pattern, role, side["prologue"].string.hex(), keys)
            }
        val tampered = if (case.test.has("tampered_step")) case.test["tampered_step"].int else 0
        val rejected = if (case.test.has("rejected_step")) case.test["rejected_step"].int else 0
        val messages = case.test["messages"].list.map { it.string }
        val payloads = case.test["payloads"].list.map { it.string }
        val reads =
            messages.indices.takeWhile { index ->
                val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
                val written = writer.writeMessage(payloads[index].hex()).value().hex()
                if (index + 1 != tampered) assertEquals(messages[index], written, "message ${index + 1}")
                val read = reader.readMessage(messages[index].hex())
                if (index + 1 == rejected) {
                    assertIs<NoiseResult.Failed>(read, "message ${index + 1}")
                } else {
                    assertEquals(payloads[index], read.value().hex(), "payload ${index + 1}")
                }
                index + 1 != rejected
            }
        if (case.result == "valid") {
            assertEquals(messages.size, reads.size)
            val (sending, receiving) = initiator.split().value() to responder.split().value()
            assertEquals(case.test["handshake_hash"].string, sending.handshakeHash.hex())
            assertEquals(case.test["handshake_hash"].string, receiving.handshakeHash.hex())
            case.test["transport"].list.forEach { message ->
                val fromInitiator = message["direction"].string == "initiator-to-responder"
                val (writer, reader) = if (fromInitiator) sending to receiving else receiving to sending
                val plaintext = message["plaintext"].string
                assertEquals(message["ciphertext"].string, writer.encrypt(plaintext.hex()).value().hex())
                assertEquals(plaintext, reader.decrypt(message["ciphertext"].string.hex()).value().hex())
            }
        } else {
            assertEquals("handshake-failed", case.test["error"].string)
            assertEquals(rejected - 1, reads.size)
        }
    }

private fun <T> NoiseResult<T>.value(): T = assertIs<NoiseResult.Ok<T>>(this).value

private const val HANDSHAKE_MESSAGES = 3
