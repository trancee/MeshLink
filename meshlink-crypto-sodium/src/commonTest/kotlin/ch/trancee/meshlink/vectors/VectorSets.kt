package ch.trancee.meshlink.vectors

/** The tier 1 layout files dispatch on the group's `layout`. */
private val layoutKey: (Json, Json) -> String = { group, _ -> group["layout"].string }

/** Frame files dispatch on the frame type, the first byte of `encoded`. */
private val frameKey: (Json, Json) -> String =
    { _, test -> test["encoded"].string.take(2).let { if (it.isEmpty()) "empty" else "0x$it" } }

/** `hpke-pq/test-vectors.json` is one array; each entry is a test, numbered from 1. */
private fun hpkeCases(document: Json): List<RawCase> =
    document.list.mapIndexed { index, entry -> RawCase(entry, entry, index + 1, "valid") }

private val kemId: (Json, Json) -> String =
    { _, test -> "kem 0x" + test["kem_id"].int.toString(HEX).padStart(KEM_ID_DIGITS, '0') }

/** Wycheproof files and the tier 2 files from `tools/reference` dispatch on the group's `type`. */
private val groupType: (Json, Json) -> String = { group, _ -> group["type"].string }

/** ML-KEM decapsulation keys with a corrupted hash or embedded key get their own key, so a skip can name them. */
private val decapsKey: (Json, Json) -> String = { group, test ->
    if (test["flags"].list.any { it.string == INVALID_DK }) INVALID_DK else group["type"].string
}

/** Every embedded vector file's set (ticket 19). A ticket that lands a codec adds its handler here. */
internal val vectorSets: List<VectorSet> =
    listOf(
        VectorSet("vectors/advertisement.json", mapOf("advertisement" to advertisement), key = layoutKey),
        VectorSet("vectors/capability.json", mapOf("capability" to capability), key = layoutKey),
        VectorSet("vectors/certificate.json", emptyMap(), key = layoutKey),
        VectorSet("vectors/certificate-signatures.json", emptyMap(), key = groupType),
        VectorSet(
            "vectors/derivations.json",
            mapOf(
                "epoch" to epoch,
                "node-id" to nodeId,
                "mesh-id-digest" to meshIdDigest,
                "mesh-tag" to meshTag,
                "unsecured-tag" to unsecuredTag,
                "tombstone-commitment" to tombstoneCommitment,
            ),
        ) { group, _ -> group["derivation"].string },
        VectorSet("vectors/handshake-hfs.json", emptyMap(), key = groupType),
        VectorSet("vectors/handshake-payload.json", emptyMap(), key = layoutKey),
        VectorSet("vectors/hpke-seal.json", emptyMap(), key = groupType),
        VectorSet(
            "vectors/inner-plaintext.json",
            mapOf("inner-message" to innerContent(message = true), "inner-receipt" to innerContent(message = false)),
            key = layoutKey,
        ),
        VectorSet(
            "vectors/link-frames.json",
            listOf("0x03", "0x04", "0x00", "0x3f", "0xc0", "0xff", "empty").associateWith { linkFrame },
            key = frameKey,
        ),
        VectorSet("vectors/records.json", mapOf("record" to record), key = layoutKey),
        VectorSet("vectors/routed-frames.json", mapOf("0x40" to messageFrame), key = frameKey),
        VectorSet("hpke-pq/test-vectors.json", mapOf("kem 0x647a" to xWing), cases = ::hpkeCases, key = kemId),
        VectorSet(
            "wycheproof/chacha20_poly1305_test.json",
            mapOf("AeadTest" to chaCha20Poly1305),
            key = groupType,
        ),
        VectorSet("wycheproof/ed25519_test.json", mapOf("EddsaVerify" to ed25519), key = groupType),
        VectorSet("wycheproof/hkdf_sha256_test.json", mapOf("HkdfTest" to hkdfSha256), key = groupType),
        VectorSet(
            "wycheproof/mlkem_768_encaps_test.json",
            mapOf("MLKEMEncapsTest" to mlKem768Encapsulate),
            key = groupType,
        ),
        VectorSet(
            "wycheproof/mlkem_768_keygen_seed_test.json",
            mapOf("MLKEMKeyGen" to mlKem768KeyGen),
            key = groupType,
        ),
        VectorSet(
            "wycheproof/mlkem_768_semi_expanded_decaps_test.json",
            mapOf("MLKEMDecapsValidationTest" to mlKem768Decapsulate),
            key = decapsKey,
        ),
        VectorSet("wycheproof/mlkem_768_test.json", mapOf("MLKEMTest" to mlKem768), key = groupType),
        VectorSet("wycheproof/x25519_test.json", mapOf("XdhComp" to x25519), key = groupType),
    )

private fun pending(
    file: String,
    ticket: Int,
    vararg keys: String,
): List<Skip> = keys.map { Skip.Pending("vectors/$file", it, ticket) }

/** Tests no handler runs yet: each names the ticket that lands the code, or why MeshLink never runs it. */
internal val vectorSkips: List<Skip> =
    pending("certificate.json", 21, "certificate") +
        pending("certificate-signatures.json", 21, "CertificateVerify") +
        pending("handshake-hfs.json", 23, "NoiseHandshake") +
        pending("hpke-seal.json", 45, "HpkeSeal") +
        pending("handshake-payload.json", 24, "handshake-payload") +
        pending("derivations.json", 24, "mesh-tag-key", "mesh-psk", "link-prologue") +
        pending("derivations.json", 27, "resolvable-tag") +
        pending("derivations.json", 45, "public-tag", "conversation-secret", "conversation-tag", "e2e-prologue") +
        pending("derivations.json", 45, "hpke-info") +
        pending("derivations.json", 44, "lookup-tag", "lookup-reply-key") +
        pending("derivations.json", 21, "certificate-signing-input") +
        pending("derivations.json", 39, "broadcast-signing-input") +
        pending("link-frames.json", 28, "0x01", "0x06") +
        pending("link-frames.json", 24, "0x02") +
        pending("link-frames.json", 30, "0x05") +
        pending("link-frames.json", 35, "0x07", "0x08", "0x09") +
        pending("link-frames.json", 41, "0x0a") +
        pending("link-frames.json", 40, "0x0b", "0x0c", "0x0d") +
        pending("link-frames.json", 27, "0x0e") +
        pending("link-frames.json", 31, "0x0f") +
        pending("routed-frames.json", 47, "0x41") +
        pending("routed-frames.json", 48, "0x42") +
        pending("routed-frames.json", 38, "0x43", "0x81") +
        pending("routed-frames.json", 44, "0x44", "0x82") +
        pending("routed-frames.json", 39, "0x80") +
        pending("routed-frames.json", 37, "0x7e", "0xbf") +
        listOf("0x0010", "0x0011", "0x0020", "0x0021", "0x0040", "0x0041", "0x0042", "0x0050", "0x0051").map {
            Skip.NotApplicable(
                "hpke-pq/test-vectors.json",
                "kem $it",
                "MeshLink's only HPKE KEM is X-Wing, 0x647a (ADR 0004)",
            )
        } +
        Skip.NotApplicable(
            "wycheproof/mlkem_768_semi_expanded_decaps_test.json",
            INVALID_DK,
            "MeshLink decapsulates only with keys it generated (ADR 0004); " +
                "the FIPS 203 section 7.3 key check is for supplied keys",
        )

private const val HEX = 16
private const val INVALID_DK = "InvalidDecapsulationKey"
private const val KEM_ID_DIGITS = 4
