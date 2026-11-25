package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CertificateCheck
import ch.trancee.meshlink.internal.DecodeError
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.StaticKeys
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IdentityCertificateTest {
    private val crypto = SodiumCryptoProvider
    private val identity = crypto.ed25519KeyPair(ByteArray(32) { 1 })
    private val other = crypto.ed25519KeyPair(ByteArray(32) { 2 })
    private val x25519Key = crypto.x25519KeyPair(ByteArray(32) { 3 }).publicKey
    private val xWingKey = crypto.xWingKeyPair(ByteArray(32) { 4 }).publicKey
    private val classicalKeys = StaticKeys(x25519Key, null)
    private val postQuantumKeys = StaticKeys(x25519Key, xWingKey)
    private val nodeId = Derivations(crypto).nodeId(identity.publicKey)

    private fun classical(validity: Duration = 24.hours) =
        IdentityCertificate.issue(crypto, FixedClock.atSeconds(NOT_BEFORE), identity, classicalKeys, validity)

    private fun validate(
        bytes: ByteArray,
        atSeconds: Long,
        profile: WireProfile = WireProfile.CLASSICAL,
        expectedNodeId: ByteArray = nodeId,
    ) = IdentityCertificate.validate(crypto, bytes, profile, expectedNodeId, FixedClock.atSeconds(atSeconds))

    private fun CertificateCheck.code(): String = assertIs<CertificateCheck.Invalid>(this).code

    @Test
    fun issuingReadsTheInjectedClockAndDefaultsToTwentyFourHours() {
        val certificate =
            IdentityCertificate.issue(crypto, FixedClock(NOT_BEFORE * 1_000 + 999), identity, postQuantumKeys)

        assertEquals(WireProfile.POST_QUANTUM, certificate.profile)
        assertEquals(NOT_BEFORE.toUInt(), certificate.notBefore)
        assertEquals((NOT_BEFORE + 86_400).toUInt(), certificate.notAfter)
        assertContentEquals(identity.publicKey, certificate.ed25519Key)
        assertContentEquals(xWingKey, certificate.xWingKey)
        assertEquals(IdentityCertificate.POST_QUANTUM_LENGTH, certificate.encode().size)
        assertIs<CertificateCheck.Valid>(validate(certificate.encode(), NOT_BEFORE, WireProfile.POST_QUANTUM))
    }

    @Test
    fun aClassicalCertificateHasNoXWingKey() {
        val certificate = classical()

        assertNull(certificate.xWingKey)
        assertEquals(IdentityCertificate.CLASSICAL_LENGTH, certificate.encode().size)
    }

    @Test
    fun issuingRefusesBadValiditiesKeysAndTimes() {
        fun issue(
            validity: Duration = 24.hours,
            atMillis: Long = NOT_BEFORE * 1_000,
        ) = IdentityCertificate.issue(crypto, FixedClock(atMillis), identity, classicalKeys, validity)

        assertFailsWith<IllegalArgumentException> { issue(validity = 24.hours + 1.seconds) }
        assertFailsWith<IllegalArgumentException> { issue(validity = 999.milliseconds) }
        assertFailsWith<IllegalArgumentException> { StaticKeys(ByteArray(31), null) }
        assertFailsWith<IllegalArgumentException> { StaticKeys(x25519Key, ByteArray(1_215)) }
        assertFailsWith<IllegalArgumentException> { issue(atMillis = -1) }
        assertFailsWith<IllegalArgumentException> { issue(atMillis = -1_000) }
        assertFailsWith<IllegalArgumentException> { issue(atMillis = (U32_MAX - 86_399) * 1_000) }
    }

    @Test
    fun aPeerHoldingThisCertificateIsRefreshedOnlyPastHalfItsValidityAndOnlyToANewerOne() {
        val held = classical()
        val newer =
            IdentityCertificate.issue(crypto, FixedClock.atSeconds(NOT_BEFORE + 1), identity, classicalKeys)
        val half = NOT_BEFORE + 12 * 3_600

        assertNull(held.refresh(newer, half - 1))
        assertNull(held.refresh(held, half))
        assertEquals(newer, held.refresh(newer, half))
    }

    @Test
    fun theValidityWindowIsHalfOpenWithThirtySecondsOfSkewOnBothEdges() {
        val bytes = classical().encode()
        val notAfter = NOT_BEFORE + 86_400

        assertIs<CertificateCheck.Valid>(validate(bytes, NOT_BEFORE - 30))
        assertEquals("not-yet-valid", validate(bytes, NOT_BEFORE - 31).code())
        assertIs<CertificateCheck.Valid>(validate(bytes, notAfter + 29))
        assertEquals("expired", validate(bytes, notAfter + 30).code())
        assertEquals("expired", validate(bytes, notAfter + 31).code())
    }

    @Test
    fun aSubSecondClockIsTruncatedToTheSecond() {
        val bytes = classical().encode()
        val lastAccepted = (NOT_BEFORE + 86_400 + 29) * 1_000 + 999

        val check = IdentityCertificate.validate(crypto, bytes, WireProfile.CLASSICAL, nodeId, FixedClock(lastAccepted))

        assertIs<CertificateCheck.Valid>(check)
    }

    @Test
    fun aClockBefore1970IsRoundedDownAndNotYetValid() {
        val bytes = classical().encode()

        listOf(-1L, -1_000L).forEach { millis ->
            val check = IdentityCertificate.validate(crypto, bytes, WireProfile.CLASSICAL, nodeId, FixedClock(millis))
            assertEquals("not-yet-valid", check.code())
        }
    }

    @Test
    fun validationFailuresAreTypedAndLeaveTheInputUnchanged() {
        val bytes = classical().encode()
        val flipped = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val otherSigner =
            bytes.copyOf(bytes.size - 64) +
                crypto.ed25519Sign(other.seed, IdentityCertificate.signingInput(bytes.copyOf(bytes.size - 64)))
        val cases =
            mapOf(
                byteArrayOf(2) + bytes.copyOfRange(1, bytes.size) to "unknown-version",
                bytes.copyOf(bytes.size - 1) to "truncated",
                bytes + 0 to "trailing-bytes",
                ByteArray(0) to "truncated",
                flipped to "bad-signature",
                otherSigner to "bad-signature",
            )

        cases.forEach { (input, code) ->
            val before = input.copyOf()
            assertEquals(code, validate(input, NOT_BEFORE).code(), code)
            assertContentEquals(before, input)
        }
        assertEquals("unknown-kind", validate(bytes, NOT_BEFORE, WireProfile.POST_QUANTUM).code())
        assertEquals("node-id-mismatch", validate(bytes, NOT_BEFORE, expectedNodeId = ByteArray(32)).code())
    }

    @Test
    fun unsecuredHasNoCertificate() {
        val bytes = classical().encode()

        assertFailsWith<IllegalArgumentException> { validate(bytes, NOT_BEFORE, WireProfile.UNSECURED) }
        val unsecured = IdentityCertificate.decode(byteArrayOf(1, 0) + bytes.copyOfRange(2, bytes.size))
        assertEquals(DecodeError.UNKNOWN_KIND, assertIs<Decoded.Invalid>(unsecured).error)
    }

    private companion object {
        const val NOT_BEFORE = 1_798_761_600L
        const val U32_MAX = 4_294_967_295L
    }
}
