package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdvertisementTest {
    @Test
    fun encodeRejectsFieldsOfTheWrongSize() {
        val tag = ByteArray(4)
        val id = ByteArray(8)
        val random = ByteArray(3)

        assertFailsWith<IllegalArgumentException> { Advertisement(32, tag, id, random) }
        assertFailsWith<IllegalArgumentException> { Advertisement(-1, tag, id, random) }
        assertFailsWith<IllegalArgumentException> { Advertisement(0, ByteArray(5), id, random) }
        assertFailsWith<IllegalArgumentException> { Advertisement(0, tag, ByteArray(7), random) }
        assertFailsWith<IllegalArgumentException> { Advertisement(0, tag, id, ByteArray(4)) }
    }

    @Test
    fun payloadMapsToTheServiceUuidInCanonicalStringOrder() {
        val payload = "2046f40543aebfd3a50d5b3f4543176d".hex()

        val uuid = Advertisement.serviceUuid(payload)

        assertEquals("2046f405-43ae-bfd3-a50d-5b3f4543176d", uuid.toString())
        assertContentEquals(payload, uuid.toByteArray())
    }
}
