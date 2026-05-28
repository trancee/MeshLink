package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

class PermissionContractTest {
    @Test
    fun `allowed authorization does not throw`() {
        // Arrange / Act
        BlePermissionContract.ensureBluetoothAuthorized(CBManagerAuthorizationAllowedAlways)

        // Assert
        // No exception means the permission contract is satisfied.
    }

    @Test
    fun `not determined authorization remains promptable`() {
        // Arrange / Act
        BlePermissionContract.ensureBluetoothAuthorized(CBManagerAuthorizationNotDetermined)

        // Assert
        // No exception means iOS can still prompt for bluetooth access.
    }

    @Test
    fun `denied authorization throws permission denied`() {
        // Arrange / Act / Assert
        assertFailsWith<PlatformPermissionDeniedException> {
            BlePermissionContract.ensureBluetoothAuthorized(CBManagerAuthorizationDenied)
        }
    }

    @Test
    fun `restricted authorization throws permission denied`() {
        // Arrange / Act / Assert
        assertFailsWith<PlatformPermissionDeniedException> {
            BlePermissionContract.ensureBluetoothAuthorized(CBManagerAuthorizationRestricted)
        }
    }
}
