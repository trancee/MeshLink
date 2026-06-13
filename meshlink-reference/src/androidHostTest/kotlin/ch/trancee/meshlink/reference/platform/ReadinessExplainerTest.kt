package ch.trancee.meshlink.reference.platform

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                "Keep the screen awake or disable battery optimization before starting MeshLink direct proof; on some Android 14 devices the live-proof foreground wake-lock mitigation is still needed to avoid quick-doze discovery stalls."
            )

        // Act
        val actual =
            readinessBlockers(missingPermissions = missingPermissions, powerManagement = expected)

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun readinessGuidanceListsTheExpectedOperationalChecklist() {
        // Arrange
        val expected =
            listOf(
                "Confirm Bluetooth is enabled and the Android device is on API 26 or newer.",
                "Use the debug install path so runtime permissions are granted where the platform allows it.",
                "Keep the device awake during direct proof on aggressive OEM builds, or rely on the live-proof foreground wake-lock mitigation when the reference app starts it; doze can stall BLE discovery.",
                "Keep the device offline and near the peer before starting the guided exchange.",
            )

        // Act
        val actual = readinessGuidance()

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun readinessBlockersFormatMissingPermissionsAndPreservePowerManagementWarnings() {
        // Arrange
        val missingPermissions =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val powerManagement =
            listOf(
                "Keep the screen awake or disable battery optimization before starting MeshLink direct proof; on some Android 14 devices the live-proof foreground wake-lock mitigation is still needed to avoid quick-doze discovery stalls."
            )

        // Act
        val actual =
            readinessBlockers(
                missingPermissions = missingPermissions,
                powerManagement = powerManagement,
            )

        // Assert
        assertEquals(3, actual.size)
        assertEquals(
            "Grant the required Android BLE permissions before starting MeshLink: BLUETOOTH_SCAN, BLUETOOTH_CONNECT.",
            actual[0],
        )
        assertTrue(actual[1].contains("Location permission"))
        assertEquals(powerManagement.single(), actual[2])
    }
}
