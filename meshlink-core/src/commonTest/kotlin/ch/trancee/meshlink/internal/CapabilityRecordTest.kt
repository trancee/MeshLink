package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CapabilityRecordTest {
    @Test
    fun namesTheKnownExtensions() {
        val withBoth = (CapabilityRecord.decode(WITH_BOTH.hex()) as Decoded.Valid).value
        val withNone = (CapabilityRecord.decode("0101".hex()) as Decoded.Valid).value

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

    private companion object {
        const val WITH_BOTH = "01010100020081020010b8773ca94fa89b92c015c45efa125ddd"
        const val CRITICAL = 0xF0
        const val UNKNOWN = 0x7F
    }
}
