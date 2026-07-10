package ch.trancee.meshlink.reference.platform

import android.Manifest
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessGuidanceAndPermissionsTest {
    @Test
    fun readinessGuidanceMentionsBluetoothAndOfflineRequirements() {
        // Arrange / Act
        val guidance = readinessGuidance()

        // Assert
        assertTrue(guidance.any { it.contains("Bluetooth") })
        assertTrue(guidance.any { it.contains("offline") })
    }

    @Test
    fun requiredPermissionsOnApi31AndAboveIncludeNearbyDeviceBluetoothPermissionsOnly() {
        // Arrange / Act
        val permissions = requiredPermissions(sdkInt = Build.VERSION_CODES.S)

        // Assert -- ACCESS_FINE_LOCATION is deliberately excluded here: the manifest declares
        // BLUETOOTH_SCAN with usesPermissionFlags="neverForLocation" and scopes
        // ACCESS_FINE_LOCATION itself to maxSdkVersion="30", so it is not even requestable on API
        // 31+ and requiring it here permanently blocked "Startup" on real API 31+ hardware.
        assertEquals(
            expected =
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                ),
            actual = permissions,
        )
    }

    @Test
    fun requiredPermissionsBelowApi31OnlyRequireFineLocation() {
        // Arrange / Act
        val permissions = requiredPermissions(sdkInt = Build.VERSION_CODES.S - 1)

        // Assert
        assertEquals(
            expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            actual = permissions,
        )
    }

    @Test
    fun noBlockersWhenAllPermissionsAreGranted() {
        // Arrange / Act
        val blockers = readinessBlockers(missingPermissions = emptyList())

        // Assert
        assertEquals(expected = emptyList(), actual = blockers)
    }

    @Test
    fun missingPermissionsProduceANamedBlockerAndALocationFollowUp() {
        // Arrange
        val missingPermissions =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)

        // Act
        val blockers = readinessBlockers(missingPermissions = missingPermissions)

        // Assert
        assertEquals(
            expected =
                listOf(
                    "Grant the required Android BLE permissions before starting MeshLink: " +
                        "BLUETOOTH_SCAN, ACCESS_FINE_LOCATION.",
                    "Some Android devices also require Location permission before BLE scan " +
                        "results become visible.",
                ),
            actual = blockers,
        )
    }

    @Test
    fun powerManagementBlockersAreAppendedAfterPermissionBlockers() {
        // Arrange
        val powerManagement = listOf("Keep the screen awake before starting MeshLink direct proof.")

        // Act
        val blockers =
            readinessBlockers(missingPermissions = emptyList(), powerManagement = powerManagement)

        // Assert
        assertEquals(expected = powerManagement, actual = blockers)
    }
}
