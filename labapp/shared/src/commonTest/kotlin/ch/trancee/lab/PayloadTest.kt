package ch.trancee.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadTest {
    @Test
    fun sha256MatchesTheFips180Vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Payload.hex(sha256("abc".encodeToByteArray())),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Payload.hex(sha256(ByteArray(0))),
        )
    }

    @Test
    fun payloadIsIntactOnlyWhileItsBodyMatchesItsDigest() {
        val payload = Payload.create("meshlink-lab")
        val altered = payload.copyOf().also { it[0] = 'M'.code.toByte() }

        assertTrue(Payload.intact(payload))
        assertFalse(Payload.intact(altered))
        assertFalse(Payload.intact(ByteArray(31)))
    }
}
