package ch.trancee.meshlink.reference.platform

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadinessExplainerTest {
    @Test
    fun requiredPermissionsIncludeBluetoothAdvertiseOnAndroid12AndNewer() {
        // Arrange
        val sdkInt = 31
        val expected =
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )

        // Act
        val actual = requiredPermissions(sdkInt)

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun requiredPermissionsFallBackToLocationBeforeAndroid12() {
        // Arrange
        val sdkInt = 30
        val expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        // Act
        val actual = requiredPermissions(sdkInt)

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun blockersExplainMissingBluetoothPermissions() {
        // Arrange
        val missingPermissions =
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        val expected =
            listOf(
                "Grant the required Android BLE permissions before starting MeshLink: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, ACCESS_FINE_LOCATION.",
                "Some Android devices also require Location permission before BLE scan results become visible.",
            )

        // Act
        val actual = readinessBlockers(missingPermissions)

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun blockersIncludeDozeWarningWhenPowerManagerReportsIdle() {
        // Arrange
        val missingPermissions = emptyList<String>()
        val expected =
            listOf(
                "Keep the screen awake or disable battery optimization before starting MeshLink direct proof; doze can stall BLE discovery on some Android 14 devices.",
            )

        // Act
        val actual = readinessBlockers(
            missingPermissions = missingPermissions,
            powerManagement = expected,
        )

        // Assert
        assertEquals(expected, actual)
    }
}
