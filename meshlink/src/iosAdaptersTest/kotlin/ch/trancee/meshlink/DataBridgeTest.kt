package ch.trancee.meshlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DataBridgeTest {
    @Test
    fun bytesSurviveTheRoundTripThroughNsData() {
        for (size in listOf(0, 1, 128 * 1024)) {
            // Arrange
            val bytes = ByteArray(size) { (it * 31 + 7).toByte() }

            // Act
            val data = bytes.toNSData()
            val back = data.toByteArray()

            // Assert
            assertEquals(size.toULong(), data.length, "length at $size B")
            assertContentEquals(bytes, back, "content at $size B")
        }
    }
}
