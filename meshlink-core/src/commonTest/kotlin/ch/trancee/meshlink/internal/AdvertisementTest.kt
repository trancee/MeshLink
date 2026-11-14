package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.advertisementVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AdvertisementTest {
    @Test
    fun decodesEveryVector() {
        // A vector file edit that drops or renumbers tests must fail here, not pass vacuously.
        assertEquals((1..10).toList(), advertisementVectors.map { it.tcId })
        advertisementVectors.forEach { vector ->
            val result = Advertisement.decode(vector.encoded.hex())
            val decoded = vector.decoded
            if (decoded == null) {
                assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
            } else {
                val advertisement = assertIs<Decoded.Valid<Advertisement>>(result, "tcId ${vector.tcId}").value
                assertEquals(decoded.getValue("layout_version").toInt(), Advertisement.LAYOUT_VERSION)
                assertEquals(decoded.getValue("flags").toInt(), advertisement.flags, "tcId ${vector.tcId}")
                assertEquals(decoded.getValue("mesh_tag"), advertisement.meshTag.hex(), "tcId ${vector.tcId}")
                assertEquals(
                    decoded.getValue("resolvable_tag"),
                    advertisement.resolvableTag.hex(),
                    "tcId ${vector.tcId}",
                )
                assertEquals(decoded.getValue("random"), advertisement.random.hex(), "tcId ${vector.tcId}")
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        advertisementVectors.filter { it.result == "valid" }.forEach { vector ->
            val advertisement = (Advertisement.decode(vector.encoded.hex()) as Decoded.Valid).value

            assertEquals(vector.encoded, advertisement.encode().hex(), "tcId ${vector.tcId}")
        }
    }

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
        val payload = advertisementVectors.first().encoded.hex()

        val uuid = Advertisement.serviceUuid(payload)

        assertEquals("2046f405-43ae-bfd3-a50d-5b3f4543176d", uuid.toString())
        assertContentEquals(payload, uuid.toByteArray())
    }

    @Test
    fun decoderSurvivesEveryPrefixOverlongInputsAndSeededMutations() {
        val valid = advertisementVectors.first().encoded.hex()
        val random = SeededRandom(MUTATION_SEED)
        val inputs =
            (0..valid.size).map { valid.copyOf(it) } +
                (1..OVERLONG_EXTRA).map { valid + ByteArray(it) } +
                (1..MUTATIONS).map {
                    valid.copyOf().also { bytes ->
                        repeat(random.nextInt(1, MAX_FLIPS + 1)) {
                            bytes[random.nextInt(0, bytes.size)] = random.nextInt(0, BYTE_VALUES).toByte()
                        }
                    }
                }

        // Reserved flag bits survive decoding, so every valid result re-encodes to its input.
        inputs.forEach { input ->
            when (val result = Advertisement.decode(input)) {
                is Decoded.Valid -> assertContentEquals(input, result.value.encode())
                is Decoded.Invalid -> Unit
            }
        }
    }

    private companion object {
        const val MUTATION_SEED = 0x4D455348L
        const val OVERLONG_EXTRA = 64
        const val MUTATIONS = 10_000
        const val MAX_FLIPS = 4
        const val BYTE_VALUES = 256
    }
}
