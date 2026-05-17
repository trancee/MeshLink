package ch.trancee.meshlink.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosBleTransportBridgeTest {
    @AfterTest
    fun tearDown(): Unit {
        IosBleTransportBridgeRegistry.clear()
    }

    @Test
    fun requireCallbacksFailsWhenBridgeIsMissing(): Unit {
        // Arrange
        IosBleTransportBridgeRegistry.clear()

        // Act
        val exception =
            assertFailsWith<MeshLinkException.PlatformFailure> {
                IosBleTransportBridgeRegistry.requireCallbacks()
            }

        // Assert
        assertTrue(
            actual = exception.message.orEmpty().contains("IosBleTransportBridge.install"),
            message =
                "Missing bridge failures should direct iOS callers to install native transport callbacks",
        )
    }

    @Test
    fun installDelegatesGattNotifySendThroughTheRegisteredBridge(): Unit {
        // Arrange
        val expectedPayload = byteArrayOf(1, 2, 3, 4)
        val expectedResult = true
        val expectedPeripheral = Any()
        val expectedCharacteristic = Any()
        val expectedCentral = Any()
        var invocationCount = 0
        IosBleTransportBridge.install(
            gattNotifySend = { peripheralManager, notifyCharacteristic, central, payload ->
                invocationCount += 1
                assertEquals(expectedPeripheral, peripheralManager)
                assertEquals(expectedCharacteristic, notifyCharacteristic)
                assertEquals(expectedCentral, central)
                assertContentEquals(expectedPayload, payload)
                expectedResult
            }
        )

        // Act
        val callbacks = IosBleTransportBridgeRegistry.requireCallbacks()
        val actual =
            callbacks.gattNotifySend(
                expectedPeripheral,
                expectedCharacteristic,
                expectedCentral,
                expectedPayload,
            )

        // Assert
        assertEquals(1, invocationCount)
        assertEquals(expectedResult, actual)
        assertNull(callbacks.gattNotifySendData)
    }

    @Test
    fun installDataDelegatesGattNotifySendThroughTheRegisteredBridge(): Unit {
        // Arrange
        val expectedPayload = Any()
        val expectedResult = true
        val expectedPeripheral = Any()
        val expectedCharacteristic = Any()
        val expectedCentral = Any()
        var invocationCount = 0
        IosBleTransportBridge.installData(
            gattNotifySendData = { peripheralManager, notifyCharacteristic, central, payloadData ->
                invocationCount += 1
                assertEquals(expectedPeripheral, peripheralManager)
                assertEquals(expectedCharacteristic, notifyCharacteristic)
                assertEquals(expectedCentral, central)
                assertEquals(expectedPayload, payloadData)
                expectedResult
            }
        )

        // Act
        val callbacks = IosBleTransportBridgeRegistry.requireCallbacks()
        val actual =
            callbacks.gattNotifySendData?.invoke(
                expectedPeripheral,
                expectedCharacteristic,
                expectedCentral,
                expectedPayload,
            )

        // Assert
        assertEquals(1, invocationCount)
        assertNotNull(actual)
        assertEquals(expectedResult, actual)
    }
}
