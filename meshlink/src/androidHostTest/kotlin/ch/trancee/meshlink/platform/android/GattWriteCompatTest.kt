package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothStatusCodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GattWriteCompatTest {
    @Test
    fun writeGattDescriptorUsesLegacyWriteBelowApi33(): Unit {
        // Arrange
        var api33Invoked = false
        var legacyInvoked = false

        // Act
        val written =
            writeGattDescriptor(
                sdkInt = 32,
                api33Write = {
                    api33Invoked = true
                    BluetoothStatusCodes.SUCCESS
                },
                legacyWrite = {
                    legacyInvoked = true
                    true
                },
            )

        // Assert
        assertTrue(written)
        assertFalse(api33Invoked)
        assertTrue(legacyInvoked)
    }

    @Test
    fun writeGattDescriptorUsesApi33ResultCodesOnApi33AndAbove(): Unit {
        // Arrange
        var legacyInvoked = false

        // Act
        val written =
            writeGattDescriptor(
                sdkInt = 33,
                api33Write = { BluetoothStatusCodes.SUCCESS },
                legacyWrite = {
                    legacyInvoked = true
                    true
                },
            )

        // Assert
        assertTrue(written)
        assertFalse(legacyInvoked)
    }

    @Test
    fun writeGattCharacteristicUsesLegacyWriteBelowApi33(): Unit {
        // Arrange
        var api33Invoked = false
        var legacyInvoked = false

        // Act
        val written =
            writeGattCharacteristic(
                sdkInt = 32,
                api33Write = {
                    api33Invoked = true
                    BluetoothStatusCodes.SUCCESS
                },
                legacyWrite = {
                    legacyInvoked = true
                    true
                },
            )

        // Assert
        assertTrue(written)
        assertFalse(api33Invoked)
        assertTrue(legacyInvoked)
    }

    @Test
    fun writeGattCharacteristicReturnsFalseWhenApi33WriteFails(): Unit {
        // Arrange
        var legacyInvoked = false

        // Act
        val written =
            writeGattCharacteristic(
                sdkInt = 33,
                api33Write = { BluetoothStatusCodes.ERROR_UNKNOWN },
                legacyWrite = {
                    legacyInvoked = true
                    true
                },
            )

        // Assert
        assertFalse(written)
        assertFalse(legacyInvoked)
    }
}
