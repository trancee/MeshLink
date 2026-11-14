package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.capabilityVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CapabilityRecordTest {
    @Test
    fun decodesEveryVector() {
        assertEquals((1..14).toList(), capabilityVectors.map { it.tcId })
        capabilityVectors.forEach { vector ->
            val result = CapabilityRecord.decode(vector.encoded.hex())
            val decoded = vector.decoded
            if (decoded == null) {
                assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
            } else {
                val record = assertIs<Decoded.Valid<CapabilityRecord>>(result, "tcId ${vector.tcId}").value
                val versions =
                    (0 until decoded.getValue("versions.count").toInt()).map {
                        decoded.getValue("versions.$it.version").toInt()
                    }
                val extensions =
                    (0 until decoded.getValue("extensions.count").toInt()).map {
                        decoded.getValue("extensions.$it.type").removePrefix("0x").toInt(HEX) to
                            decoded.getValue("extensions.$it.value")
                    }
                assertEquals(versions, record.versions, "tcId ${vector.tcId}")
                assertEquals(extensions, record.extensions.map { it.type to it.value.hex() }, "tcId ${vector.tcId}")
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        capabilityVectors.filter { it.result == "valid" }.forEach { vector ->
            val record = (CapabilityRecord.decode(vector.encoded.hex()) as Decoded.Valid).value

            assertEquals(vector.encoded, record.encode().hex(), "tcId ${vector.tcId}")
        }
    }

    @Test
    fun namesTheKnownExtensions() {
        val withBoth = (CapabilityRecord.decode(capabilityVectors[1].encoded.hex()) as Decoded.Valid).value
        val withNone = (CapabilityRecord.decode(capabilityVectors[0].encoded.hex()) as Decoded.Valid).value

        assertEquals(0x0081, withBoth.l2capPsm)
        assertEquals("b8773ca94fa89b92c015c45efa125ddd", withBoth.advertisement?.hex())
        assertNull(withNone.l2capPsm)
        assertNull(withNone.advertisement)
    }

    @Test
    fun buildsTheRecordANodeServes() {
        val advertisement = ByteArray(RadioPort.ADVERTISEMENT_LENGTH) { it.toByte() }

        val record = CapabilityRecord.of(listOf(1), advertisement)

        assertEquals(listOf(1), record.versions)
        assertContentEquals(advertisement, record.advertisement)
        assertNull(record.l2capPsm)
    }

    @Test
    fun rejectsRecordsTheCodecWouldRefuse() {
        val psm = Tlv(CapabilityRecord.L2CAP_PSM, ByteArray(2))
        val advertisement = Tlv(CapabilityRecord.ADVERTISEMENT, ByteArray(RadioPort.ADVERTISEMENT_LENGTH))

        assertFailsWith<IllegalArgumentException> { CapabilityRecord(emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord((9 downTo 1).toList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(1, 2), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(0), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(256), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(1), listOf(advertisement, psm)) }
        assertFailsWith<IllegalArgumentException> {
            CapabilityRecord(listOf(1), listOf(Tlv(CapabilityRecord.L2CAP_PSM, ByteArray(3))))
        }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(1), listOf(Tlv(CRITICAL, ByteArray(0)))) }
        assertFailsWith<IllegalArgumentException> { CapabilityRecord(listOf(1), listOf(Tlv(UNKNOWN, ByteArray(510)))) }
        assertFailsWith<IllegalArgumentException> { Tlv(256, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Tlv(-1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Tlv(CRITICAL, ByteArray(65_536)) }
    }

    @Test
    fun decoderSurvivesEveryPrefixOverlongInputsAndSeededMutations() {
        val inputs = mutationInputs(capabilityVectors[1].encoded.hex())

        inputs.forEach { input ->
            when (val result = CapabilityRecord.decode(input)) {
                is Decoded.Valid -> assertContentEquals(input, result.value.encode())
                is Decoded.Invalid -> Unit
            }
        }
    }

    private companion object {
        const val HEX = 16
        const val CRITICAL = 0xF0
        const val UNKNOWN = 0x7F
    }
}
