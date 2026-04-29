# Project Wycheproof — Integration Patterns

<kotlin_jvm_pattern>
## Kotlin/JVM Integration Pattern

### Deserializing Test Vectors

Using `kotlinx.serialization`:

```kotlin
@Serializable
data class WycheproofFile(
    val algorithm: String,
    val schema: String,
    val generatorVersion: String,
    val numberOfTests: Int,
    val notes: Map<String, NoteEntry> = emptyMap(),
    val testGroups: List<TestGroup>
)

@Serializable
data class NoteEntry(
    val bugType: String = "",
    val description: String = ""
)

@Serializable
data class TestGroup(
    val keySize: Int = 0,
    val ivSize: Int = 0,
    val tagSize: Int = 0,
    val type: String = "",
    val tests: List<TestVector>
)

@Serializable
data class TestVector(
    val tcId: Int,
    val comment: String = "",
    val flags: List<String> = emptyList(),
    val key: String = "",
    val iv: String = "",
    val aad: String = "",
    val msg: String = "",
    val ct: String = "",
    val tag: String = "",
    val result: String  // "valid", "invalid", "acceptable"
)
```

Adapt the data classes per algorithm — ECDSA groups have `keyDer`/`keyPem`/`sha`, HKDF tests have `ikm`/`salt`/`info`/`size`/`okm`, etc.

### Generic Test Runner

```kotlin
fun runWycheproofTests(
    resourcePath: String,
    testFn: (group: TestGroup, tc: TestVector) -> Boolean
): List<String> {
    val json = Json { ignoreUnknownKeys = true }
    val data = json.decodeFromString<WycheproofFile>(
        loadResource(resourcePath)
    )

    val failures = mutableListOf<String>()
    for (group in data.testGroups) {
        for (tc in group.tests) {
            val success = try { testFn(group, tc) } catch (_: Exception) { false }

            when (tc.result) {
                "valid" -> if (!success)
                    failures += "tc#${tc.tcId}: valid vector rejected (${tc.flags})"
                "invalid" -> if (success)
                    failures += "tc#${tc.tcId}: invalid vector accepted (${tc.flags})"
                // "acceptable" — either outcome is fine
            }
        }
    }
    return failures
}
```

### AEAD Example (JCA)

```kotlin
@Test
fun testAesGcm() {
    val failures = runWycheproofTests("aes_gcm_test.json") { group, tc ->
        val key = SecretKeySpec(tc.key.hexToBytes(), "AES")
        val iv = tc.iv.hexToBytes()
        val aad = tc.aad.hexToBytes()
        val ct = tc.ct.hexToBytes()
        val tag = tc.tag.hexToBytes()
        val msg = tc.msg.hexToBytes()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(group.tagSize, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ct + tag)
        plaintext.contentEquals(msg)
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
}
```
</kotlin_jvm_pattern>

<algorithm_patterns>
## Algorithm-Specific Testing Patterns

### AEAD (AES-GCM, ChaCha20-Poly1305, AEGIS, etc.)

**Inputs:** `key`, `iv` (nonce), `aad`, `msg` (plaintext), `ct` (ciphertext), `tag`

**Test flow:**
1. For `valid`: encrypt(key, iv, aad, msg) → verify ct and tag match; decrypt(key, iv, aad, ct‖tag) → verify plaintext matches
2. For `invalid`: decrypt MUST fail (corrupted ct, wrong tag, wrong key, etc.)

**Watch for:**
- Counter wrap (GCM)
- Nonce reuse detection
- Non-standard IV sizes (GCM supports arbitrary, not just 96-bit)
- Non-standard tag sizes (32, 64, 96 bits — check `tagSize` in group)
- AES-SIV-CMAC: tag is prepended (tag‖ct), not appended

### Digital Signatures (ECDSA, EdDSA, RSA, DSA)

**Inputs:** Public key (DER/PEM/JWK in group), `msg`, `sig`

**Test flow:**
1. Load public key from group (use whichever format your API accepts)
2. For each vector: verify(pubkey, msg, sig)
3. `valid` → MUST verify; `invalid` → MUST NOT verify

**Watch for:**
- Signature malleability (s vs n-s in ECDSA)
- BER vs DER encoding (some libraries accept BER, some don't)
- Wrong hash function
- Small r/s values
- P1363 vs ASN.1 encoding — use the right test file for your API's format

### Key Exchange (ECDH, X25519, X448)

**Inputs:** `private` key, `public` key, expected `shared` secret

**Test flow:**
1. Compute shared_secret = DH(private, public)
2. `valid` → must match expected; `invalid` → must reject
3. Some invalid vectors compute shared with wrong curve — to detect curve confusion

**Watch for:**
- Invalid curve attacks (public key not on the curve)
- Low-order points (shared secret = all zeros)
- Twist points
- Public key from different curve than expected
- For X25519: libraries may compute with low-order points and return all-zero, which should be rejected

### MAC (HMAC, CMAC, KMAC, SipHash)

**Inputs:** `key`, `msg`, `tag`

**Test flow:**
1. Compute MAC(key, msg), truncate to `tagSize/8` bytes
2. Compare with expected `tag`
3. `valid` → must match; `invalid` → must not match

**Watch for:**
- Tag truncation — `tagSize` in group may differ from algorithm's native output
- Key length edge cases (HMAC with keys longer than hash block size)

### KDF (HKDF, PBKDF2)

**HKDF inputs:** `ikm`, `salt`, `info`, `size` (output length in bytes), `okm` (expected output)
**PBKDF2 inputs:** `password`, `salt`, `iterationCount`, `dkLen`, `dk` (expected derived key)

**Test flow:** Derive key material, compare with expected output.

### RSA Encryption (OAEP, PKCS#1 v1.5)

**Inputs:** Private key (in group), `msg` (expected plaintext), `ct` (ciphertext)

**Test flow:**
1. Decrypt ct using private key
2. `valid` → plaintext must match msg; `invalid` → must reject

**Watch for:**
- Bleichenbacher attacks (PKCS#1 v1.5 padding oracle)
- Manger's attack (OAEP)
- Timing side channels in error handling
</algorithm_patterns>

<ci_integration>
## CI/CD Integration

### GitHub Actions

```yaml
name: Crypto Tests
on: [push, pull_request]

jobs:
  wycheproof:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive  # pull wycheproof submodule

      - name: Run Wycheproof tests
        run: ./gradlew test --tests '*Wycheproof*'
```

### Keeping Vectors Updated

```bash
git submodule update --remote tests/wycheproof
# Run tests to catch regressions with new vectors
```

New vectors are added periodically — update regularly to benefit from new attack coverage.

### Go Module Integration

```go
import "github.com/C2SP/wycheproof"

func TestWithEmbed(t *testing.T) {
    data, err := wycheproof.ReadFile("testvectors_v1/aes_gcm_test.json")
    if err != nil {
        t.Fatal(err)
    }
    // ... parse and test
}
```
</ci_integration>

<failure_triage>
## Failure Triage Checklist

When a test fails:

1. **Check `result`** — Is it `valid`, `invalid`, or `acceptable`?

2. **Check `flags`** — Look each flag up in the top-level `notes` dictionary

3. **Check `bugType`** — Prioritize by severity:
   - 🔴 `CONFIDENTIALITY`, `AUTH_BYPASS`, `KNOWN_BUG` → Fix immediately
   - 🟠 `MISSING_STEP`, `WRONG_PRIMITIVE`, `MODIFIED_PARAMETER` → High priority
   - 🟡 `SIGNATURE_MALLEABILITY`, `CAN_OF_WORMS`, `EDGE_CASE` → Investigate
   - 🟢 `BER_ENCODING`, `LEGACY`, `FUNCTIONALITY`, `WEAK_PARAMS` → Policy decision

4. **Check `cves`** — The notes entry may link to known vulnerabilities

5. **Check `comment`** — Often describes the specific attack or edge case being tested

### Handling "acceptable" Failures

If your code rejects an acceptable vector, check the flags:
- **BER_ENCODING** → strict DER library correctly rejects BER. Not a bug.
- **WEAK_PARAMS** → security policy rejects <112-bit. Expected.
- **LEGACY** → rejecting legacy compat is fine for new code.

If your code accepts an acceptable vector:
- Verify the output is correct (matches expected)
- Consider whether your security policy should reject it

### Cross-Referencing

For algorithm-specific context, see the `doc/` directory in the repo:
- `doc/ecdh.md` — ECDH attack details (invalid curve, twist, cofactor)
- `doc/ecdsa.md` — ECDSA signature malleability, encoding issues
- `doc/rsa.md` — RSA attacks (Bleichenbacher, Manger, PKCS#1)
- `doc/dsa.md` — DSA parameter validation
- `doc/dh.md` — Finite-field DH attacks
- `doc/hkdf.md` — HKDF edge cases
- `doc/bugs.md` — Notable historic bugs found by Wycheproof testing
</failure_triage>
