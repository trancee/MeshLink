package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PermissionContractTest {
    @Test
    fun `api 31 and above require nearby-device bluetooth permissions`() {
        // Arrange
        val grantedPermissions =
            setOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
            )

        // Act
        val error =
            assertFailsWith<PlatformPermissionDeniedException> {
                BlePermissionContract.ensureRequiredPermissionsGranted(
                    sdkInt = 31,
                    isGranted = grantedPermissions::contains,
                )
            }

        // Assert
        assertEquals(
            "Android BLE permissions denied: android.permission.BLUETOOTH_SCAN",
            error.message,
        )
    }

    @Test
    fun `api 30 and below require fine location permission`() {
        // Arrange / Act
        val error =
            assertFailsWith<PlatformPermissionDeniedException> {
                BlePermissionContract.ensureRequiredPermissionsGranted(
                    sdkInt = 30,
                    isGranted = { false },
                )
            }

        // Assert
        assertEquals(
            "Android BLE permissions denied: android.permission.ACCESS_FINE_LOCATION",
            error.message,
        )
    }

    @Test
    fun `granted permissions pass without exception`() {
        // Arrange
        val grantedPermissions = BlePermissionContract.requiredPermissions(sdkInt = 31).toSet()

        // Act
        BlePermissionContract.ensureRequiredPermissionsGranted(
            sdkInt = 31,
            isGranted = grantedPermissions::contains,
        )

        // Assert
        // No exception means the permission contract is satisfied.
    }
}
