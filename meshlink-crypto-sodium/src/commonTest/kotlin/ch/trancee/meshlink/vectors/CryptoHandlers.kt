package ch.trancee.meshlink.vectors

import ch.trancee.meshlink.crypto.sodium.FixedClock
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.crypto.sodium.assertFailed
import ch.trancee.meshlink.crypto.sodium.hex
import ch.trancee.meshlink.crypto.sodium.ok
import ch.trancee.meshlink.internal.BroadcastFrame
import ch.trancee.meshlink.internal.CertificateCheck
import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.EndToEndTags
import ch.trancee.meshlink.internal.HandshakePayload
import ch.trancee.meshlink.internal.Hpke
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.KeyPair
import ch.trancee.meshlink.internal.LinkCipher
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.LookupSeal
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.RecordReader
import ch.trancee.meshlink.internal.StaticKeys
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private val crypto: CryptoProvider = SodiumCryptoProvider
private val derivations = Derivations(crypto)
private val endToEndTags = EndToEndTags(derivations)

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

internal val resolvableTag: Handler =
    derivation { derivations.resolvableTag(it["resolving_key"].string.hex(), it["epoch"].uInt) }

internal val meshTagKey: Handler =
    derivation { derivations.meshTagKey(it["mesh_key"].string.hex(), it["mesh_id_digest"].string.hex()) }

internal val meshPsk: Handler =
    derivation { derivations.meshPsk(it["mesh_key"].string.hex(), it["mesh_id_digest"].string.hex()) }

internal val linkPrologue: Handler =
    derivation { inputs ->
        Derivations.linkPrologue(
            inputs["versions"].list.map { it.int },
            inputs["selected"].int,
            WireProfile.entries.single { it.code == inputs["profile"].int },
            inputs["mesh_id_digest"].string.hex(),
        )
    }

internal val tombstoneCommitment: Handler = derivation { derivations.tombstoneCommitment(it["k"].string.hex()) }

internal val lookupTag: Handler =
    derivation { derivations.lookupTag(it["salt"].string.hex(), it["node_id"].string.hex()) }

internal val publicTag: Handler =
    derivation { endToEndTags.publicTag(it["x25519_key"].string.hex(), it["epoch"].uInt) }

internal val conversationSecret: Handler =
    derivation {
        endToEndTags.conversationSecret(
            it["seed"].string.hex(),
            it["initiator_node_id"].string.hex(),
            it["responder_node_id"].string.hex(),
        )
    }

internal val conversationTag: Handler =
    derivation {
        endToEndTags.conversationTag(it["conversation_secret"].string.hex(), it["direction"].int, it["epoch"].uInt)
    }

internal val e2ePrologue: Handler =
    derivation { inputs ->
        Derivations.e2ePrologue(inputs["version"].int, WireProfile.entries.single { it.code == inputs["profile"].int })
    }

internal val hpkeInfo: Handler =
    derivation { Derivations.hpkeInfo(it["sender_static_key"].string.hex(), it["recipient_node_id"].string.hex()) }

internal val lookupReplyKey: Handler =
    derivation { inputs ->
        LookupSeal(crypto).key(
            inputs["shared_secret"].string.hex(),
            inputs["salt"].string.hex(),
            inputs["lookup_tag"].string.hex(),
            inputs["requester_ephemeral"].string.hex(),
            inputs["responder_ephemeral"].string.hex(),
        )
    }

internal val broadcastSigningInput: Handler =
    derivation { BroadcastFrame.signingInput(it["frame_without_signature"].string.hex()) }

/**
 * `broadcast-signatures.json` (tier 2): the signing input, then a verified origin with the test's
 * `node_id` exactly for a valid test. A test with the signer's `ed25519_seed` must reproduce the
 * signature byte for byte, and a valid one must be re-signed byte for byte from its parts.
 */
internal val broadcastVerify: Handler =
    Handler("frame") { case ->
        val bytes = case.hex("frame")
        val frame = assertIs<Decoded.Valid<BroadcastFrame>>(BroadcastFrame.decode(bytes)).value
        val signingInput = BroadcastFrame.signingInput(bytes.copyOf(bytes.size - SIGNATURE_LENGTH))
        assertEquals(case.test["signing_input"].string, signingInput.hex(), "signing_input")
        val origin = frame.verifiedOrigin(crypto)
        if (case.test.has("ed25519_seed")) {
            val signature = crypto.ed25519Sign(case.hex("ed25519_seed"), signingInput)
            assertEquals(bytes.copyOfRange(bytes.size - SIGNATURE_LENGTH, bytes.size).hex(), signature.hex())
        }
        if (case.result == "valid") {
            assertEquals(case.test["node_id"].string, origin?.hex(), "node_id")
            val payload = (frame.container as Container.SignedBroadcast).payload
            val identity = crypto.ed25519KeyPair(case.hex("ed25519_seed"))
            assertContentEquals(bytes, BroadcastFrame.sign(crypto, frame.header, identity, payload).encode())
        } else {
            assertNull(origin, "origin")
        }
    }

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

/**
 * `lookup-reply.json`: both sides of a lookup reply. The responder seals `certificate` to `sealed` byte
 * for byte, or refuses a low-order requester key; the requester opens `sealed` to a certificate that
 * validates for `target_node_id`, or yields nothing for an `invalid` test.
 */
internal val lookupReplySeal: Handler =
    Handler("sealed") { case ->
        val tag = derivations.lookupTag(case.hex("salt"), case.hex("target_node_id"))
        assertEquals(case.test["lookup_tag"].string, tag.hex(), "lookup_tag")
        case.lookupSide("responder")?.let { lookupResponder(case, it) }
        case.lookupSide("requester")?.let { lookupRequester(case, it) }
    }

private fun VectorCase.lookupSide(side: String): KeyPair? =
    if (test.has("${side}_private")) {
        crypto.x25519KeyPair(hex("${side}_private")).also {
            assertEquals(test["${side}_ephemeral"].string, it.publicKey.hex(), "${side}_ephemeral")
        }
    } else {
        null
    }

private fun lookupResponder(
    case: VectorCase,
    responder: KeyPair,
) {
    val requesterKey = case.hex("requester_ephemeral")
    val (salt, tag) = case.hex("salt") to case.hex("lookup_tag")
    val sealed = LookupSeal(crypto).seal(responder, requesterKey, salt, tag, case.hex("certificate"))
    when {
        !case.test.has("sealed") -> assertNull(sealed, "a low-order requester key is refused")
        case.result == "valid" -> assertEquals(case.test["sealed"].string, sealed?.hex(), "sealed")
        else -> Unit
    }
}

private fun lookupRequester(
    case: VectorCase,
    requester: KeyPair,
) {
    val (salt, tag) = case.hex("salt") to case.hex("lookup_tag")
    val (requesterKey, responderKey) = case.hex("requester_ephemeral") to case.hex("responder_ephemeral")
    when (val shared = crypto.x25519(requester.secretKey, responderKey)) {
        is CryptoResult.Ok -> {
            assertEquals(case.test["shared_secret"].string, shared.value.hex(), "shared_secret")
            val key = LookupSeal(crypto).key(shared.value, salt, tag, requesterKey, responderKey)
            assertEquals(case.test["key"].string, key.hex(), "key")
        }

        is CryptoResult.Failed -> {
            assertEquals(case.test["error"].string, "low-order-key")
        }
    }
    val opened = LookupSeal(crypto).open(requester, responderKey, salt, tag, case.hex("sealed"))
    if (case.result == "valid") {
        assertContentEquals(case.hex("certificate"), opened, "opened")
        val certificate = assertNotNull(opened)
        val clock = FixedClock.atSeconds(case.test["now"].long)
        val decoded = assertIs<Decoded.Valid<IdentityCertificate>>(IdentityCertificate.decode(certificate))
        val profile = decoded.value.profile
        val check = IdentityCertificate.validate(crypto, certificate, profile, case.hex("target_node_id"), clock)
        assertIs<CertificateCheck.Valid>(check)
    } else {
        assertNull(opened, "an invalid reply yields no certificate")
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
 * draft-ietf-hpke-pq-05 Appendix A.5, X-Wing (`0x647a`), HKDF-SHA256, ChaCha20Poly1305, base mode.
 * `skRm` is the X-Wing seed and `ikmE` the encapsulation seed. Both the sender's and the
 * receiver's context must reach the key schedule, then the sender seals every encryption in order
 * and the receiver opens it; both export every value.
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
        hpkeContexts(case, keyPair)
    }

private fun hpkeContexts(
    case: VectorCase,
    keyPair: KeyPair,
) {
    val info = case.hex("info")
    val sender = Hpke(crypto).sender(keyPair.publicKey, info, case.hex("ikmE")).ok()
    assertEquals(case.test["enc"].string, sender.enc.hex(), "sender enc")
    val receiver = Hpke(crypto).receiver(keyPair.secretKey, case.hex("enc"), info).ok()
    for (context in listOf(sender.context, receiver)) {
        assertEquals(case.test["key"].string, context.key.hex(), "key")
        assertEquals(case.test["base_nonce"].string, context.baseNonce.hex(), "base_nonce")
        assertEquals(case.test["exporter_secret"].string, context.exporterSecret.hex(), "exporter_secret")
    }
    case.test["encryptions"].list.forEachIndexed { index, encryption ->
        val aad = encryption["aad"].string.hex()
        val ct = sender.context.seal(aad, encryption["pt"].string.hex())
        assertEquals(encryption["ct"].string, ct.hex(), "encryption ${index + 1}")
        assertEquals(encryption["pt"].string, receiver.open(aad, ct).ok().hex(), "opened ${index + 1}")
    }
    for (export in case.test["exports"].list) {
        val exporterContext = export["exporter_context"].string.hex()
        for (context in listOf(sender.context, receiver)) {
            val exported = context.export(exporterContext, export["L"].int)
            assertEquals(export["exported_value"].string, exported.hex(), "export")
        }
    }
}

/**
 * `hpke-seal.json` (tier 2): the first-message `info` from its parts, then the payload opened with
 * the recipient's X-Wing seed. A valid test must reseal to the payload byte for byte; an invalid
 * one must fail its tag.
 */
internal val hpkeSeal: Handler =
    Handler("payload") { case ->
        val info = Derivations.hpkeInfo(case.hex("sender_static_key"), case.hex("recipient_node_id"))
        assertEquals(case.test["info"].string, info.hex(), "info")
        val publicKey = crypto.xWingKeyPair(case.hex("recipient_secret_key")).publicKey
        assertEquals(case.test["recipient_public_key"].string, publicKey.hex(), "recipient_public_key")
        val opened = Hpke(crypto).open(case.hex("recipient_secret_key"), info, case.hex("payload"))
        if (case.result == "valid") {
            assertEquals(case.test["plaintext"].string, opened.ok().hex(), "plaintext")
            val sealed = Hpke(crypto).seal(publicKey, info, case.hex("plaintext"), case.hex("encapsulation_randomness"))
            assertEquals(case.test["payload"].string, sealed.ok().hex(), "payload")
        } else {
            opened.assertFailed(CryptoFailure.AUTHENTICATION_FAILED)
        }
    }

/**
 * `first-message.json` (tier 2): both sides hold the e2e prologue of the group's profile. The
 * initiator writes `payload` from its keys and the responder key it holds, and its message must
 * equal `message` except in a `Tampered` test. The responder reads `message` to `payload`; for
 * `IKhfs` it opens the seal to `plaintext`, which must reseal to `payload`. `handshake-failed` means
 * the read fails; `open-failed` that the read succeeds and the seal does not open.
 */
internal val firstMessage: Handler =
    Handler("message") { case ->
        val pattern = NoisePattern.entries.single { it.protocolName == case.group["protocol"].string }
        val profile = WireProfile.entries.single { it.code == case.group["profile"].int }
        val prologue = Derivations.e2ePrologue(1, profile)
        val (initiatorSide, responderSide) = case.test["initiator"] to case.test["responder"]
        listOf(initiatorSide, responderSide).forEach { assertEquals(it["prologue"].string, prologue.hex()) }
        val initiatorStatic = crypto.x25519KeyPair(initiatorSide["static_private"].string.hex())
        val initiatorKeys =
            NoiseKeys(
                initiatorStatic,
                ephemeral = crypto.x25519KeyPair(initiatorSide["ephemeral_private"].string.hex()),
                kemKeyPair = initiatorSide.optional("kem_keygen_seed")?.hex()?.let(crypto::mlKem768KeyPair),
                remoteStatic = case.hex("responder_key"),
            )
        val responderKeys = NoiseKeys(crypto.x25519KeyPair(responderSide["static_private"].string.hex()))
        val initiator = NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, initiatorKeys)
        val responder = NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, responderKeys)
        val (payload, message) = case.test["payload"].string to case.test["message"].string
        val written = initiator.writeMessage(payload.hex()).value().hex()
        assertEquals("Tampered" in case.test["flags"].list.map { it.string }, written != message, "message")
        val read = responder.readMessage(message.hex())
        val error =
            when {
                read is NoiseResult.Failed -> "handshake-failed"
                case.test.has("info") -> openFirstMessage(case, initiatorStatic.publicKey, read.value())
                else -> null.also { assertEquals(case.test["plaintext"].string, read.value().hex(), "plaintext") }
            }
        if (read is NoiseResult.Ok) assertEquals(payload, read.value.hex(), "payload")
        assertEquals(case.test.optional("error"), error)
    }

/** Opens an `IKhfs` first-message seal: `null` if it opens to `plaintext` and reseals to [payload]. */
private fun openFirstMessage(
    case: VectorCase,
    initiatorKey: ByteArray,
    payload: ByteArray,
): String? {
    val info = Derivations.hpkeInfo(initiatorKey, case.hex("responder_node_id"))
    assertEquals(case.test["info"].string, info.hex(), "info")
    val secretKey = case.test["responder"]["xwing_secret_key"].string.hex()
    val opened = Hpke(crypto).open(secretKey, info, payload)
    if (opened !is CryptoResult.Ok) {
        opened.assertFailed(CryptoFailure.AUTHENTICATION_FAILED)
        return "open-failed"
    }
    assertEquals(case.test["plaintext"].string, opened.value.hex(), "plaintext")
    val publicKey = crypto.xWingKeyPair(secretKey).publicKey
    val sealed = Hpke(crypto).seal(publicKey, info, opened.value, case.hex("encapsulation_randomness"))
    assertContentEquals(payload, sealed.ok(), "reseal")
    return null
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
                        remoteStatic = case.test.optional("${side}_remote_static")?.hex(),
                    )
                NoiseHandshake(crypto, pattern, role, case.hex("${side}_prologue"), keys)
            }
        val messages = case.test["messages"].list
        val handshake =
            messages.indices.asSequence().takeWhile { !initiator.isComplete }.count { index ->
                val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
                val payload = messages[index]["payload"].string
                val ciphertext = writer.writeMessage(payload.hex()).value().hex()
                assertEquals(messages[index]["ciphertext"].string, ciphertext, "message $index")
                assertEquals(payload, reader.readMessage(ciphertext.hex()).value().hex())
                true
            }
        val (sending, receiving) = initiator.split().value() to responder.split().value()
        assertEquals(case.test["handshake_hash"].string, sending.handshakeHash.hex())
        assertEquals(case.test["handshake_hash"].string, receiving.handshakeHash.hex())
        for (index in handshake until messages.size) {
            val (writer, reader) = if (index % 2 == 0) sending to receiving else receiving to sending
            val payload = messages[index]["payload"].string
            val ciphertext = writer.encrypt(payload.hex()).value().hex()
            assertEquals(messages[index]["ciphertext"].string, ciphertext, "message $index")
            assertEquals(payload, reader.decrypt(ciphertext.hex()).value().hex())
        }
    }

/**
 * `handshake-xx.json` and `handshake-hfs.json` (tier 2): each side replays its handshake from its
 * keys and, for `XXhfs`, its KEM input. A writer's message must equal the delivered one except at
 * `tampered_step`; the reader of `rejected_step` must fail and every earlier read must succeed. A
 * valid run must reach the handshake hash on both sides, carry in messages 2 and 3 certificates of
 * the pattern's profile that prove each side's node id and bind its static key, and reproduce each
 * transport message.
 */
internal val noiseHandshake: Handler =
    Handler("handshake_hash") { case ->
        val pattern = NoisePattern.entries.single { it.protocolName == case.group["protocol"].string }
        val (initiator, responder) = handshakeSides(pattern, case.test)
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
            val profile = if (pattern.name.startsWith("XX_HFS")) WireProfile.POST_QUANTUM else WireProfile.CLASSICAL
            listOf(2 to "responder", 3 to "initiator").forEach { (step, name) ->
                val decoded = HandshakePayload.decode(payloads[step - 1].hex())
                val payload = assertIs<Decoded.Valid<HandshakePayload>>(decoded)
                val clock = FixedClock.atSeconds(case.test["now"].long)
                val nodeId = case.test["${name}_node_id"].string.hex()
                val bytes = assertNotNull(payload.value.certificate, "certificate $step")
                val check = IdentityCertificate.validate(crypto, bytes, profile, nodeId, clock)
                val certificate = assertIs<CertificateCheck.Valid>(check, "certificate $step").certificate
                val static = crypto.x25519KeyPair(case.test[name]["static_private"].string.hex()).publicKey
                assertContentEquals(static, certificate.x25519Key, "static key $step")
            }
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

/**
 * `link-transport.json` (tier 2): both sides run the group's handshake, then each seals its frames
 * through a [LinkCipher]. A writer's records must equal its stream except at `rejected_record`. The
 * reader cuts the stream with a [RecordReader] and must open every record to its frame, rekeying
 * after `REKEY`, up to `rejected_record`, which must fail.
 */
internal val linkTransport: Handler =
    Handler("initiator_stream") { case ->
        val pattern = NoisePattern.entries.single { it.protocolName == case.group["protocol"].string }
        val (initiator, responder) = handshakeSides(pattern, case.group)
        case.group["payloads"].list.forEachIndexed { index, payload ->
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            reader.readMessage(writer.writeMessage(payload.string.hex()).value()).value()
        }
        val transports = listOf(initiator, responder).map { it.split().value() }
        transports.forEach { assertEquals(case.group["handshake_hash"].string, it.handshakeHash.hex()) }
        val (sending, receiving) = transports.map { LinkCipher(it, FixedClock(0)) }
        val rejected = if (case.test.has("rejected_record")) case.test["rejected_record"].int else 0
        val directions = listOf(Triple("initiator", sending, receiving), Triple("responder", receiving, sending))
        directions.forEach { (name, writer, reader) ->
            val frames = case.test["${name}_frames"].list.map { it.string }
            val bodies = assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(case.hex("${name}_stream"))).value
            assertEquals(frames.size, bodies.size, "$name records")
            frames.zip(bodies).forEachIndexed { index, (frame, body) ->
                val record = index + 1
                val written = writer.seal(frame.hex()).hex()
                if (record != rejected) assertEquals(LinkRecord.encode(body).hex(), written, "$name record $record")
                val opened = reader.open(body)
                if (name == "initiator" && record == rejected) {
                    assertSame(LinkCipher.Opened.Failed, opened, "$name record $record")
                } else {
                    assertEquals(frame, assertIs<LinkCipher.Opened.Frame>(opened).bytes.hex(), "$name record $record")
                }
            }
        }
        assertEquals(if (case.result == "valid") null else "decrypt-failed", case.test.optional("error"))
    }

/** The initiator and responder of [pattern], from the keys in [sides]' `initiator` and `responder`. */
private fun handshakeSides(
    pattern: NoisePattern,
    sides: Json,
): List<NoiseHandshake> =
    listOf(NoiseRole.INITIATOR to "initiator", NoiseRole.RESPONDER to "responder").map { (role, name) ->
        val side = sides[name]
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

private fun Json.optional(field: String): String? = if (has(field)) this[field].string else null

private fun <T> NoiseResult<T>.value(): T = assertIs<NoiseResult.Ok<T>>(this).value
