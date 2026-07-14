package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothSocketException
import android.os.Build
import ch.trancee.meshlink.platform.android.l2cap.isRetryableL2capConnectFailure
import ch.trancee.meshlink.platform.android.l2cap.isTerminalL2capErrorCode
import ch.trancee.meshlink.platform.android.l2cap.l2capConnectFailureErrorCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2capConnectFailureClassificationTest {
    @Test
    fun plainIoExceptionIsTreatedAsRetryableWithNoErrorCode(): Unit {
        // Arrange: on a host JVM (Build.VERSION.SDK_INT defaults below 34, and this is not a
        // BluetoothSocketException regardless), the classifier has no machine-readable error code
        // to branch on and must fall back to treating the failure as transient/retryable rather
        // than silently giving up on the first failure.
        val error = IOException("connect failed")

        // Act
        val retryable = isRetryableL2capConnectFailure(error)
        val errorCode = l2capConnectFailureErrorCode(error)

        // Assert
        assertTrue(retryable)
        assertNull(errorCode)
    }

    @Test
    fun preApi34PlatformTreatsEveryFailureAsRetryableEvenWithAKnownTerminalErrorCode(): Unit {
        // Arrange: sdkInt is injected below 34, so no error code is consulted at all -- this
        // exercises the "sdkInt too low" branch independent of Build.VERSION.SDK_INT on the host.
        val error = IOException("connect failed")

        // Act
        val retryable = isRetryableL2capConnectFailure(error, sdkInt = Build.VERSION_CODES.TIRAMISU)
        val errorCode = l2capConnectFailureErrorCode(error, sdkInt = Build.VERSION_CODES.TIRAMISU)

        // Assert
        assertTrue(retryable)
        assertNull(errorCode)
    }

    @Test
    fun terminalErrorCodesAreClassifiedAsTerminal(): Unit {
        // Arrange: real BluetoothSocketException.* error-code constants -- these are plain
        // compile-time Int constants and remain usable on the host JVM even though constructing
        // or invoking methods on a real BluetoothSocketException instance is not.
        val terminalCodes =
            listOf(
                BluetoothSocketException.BLUETOOTH_OFF_FAILURE,
                BluetoothSocketException.NULL_DEVICE,
                BluetoothSocketException.L2CAP_CLIENT_SECURITY_FAILURE,
                BluetoothSocketException.L2CAP_INSUFFICIENT_AUTHENTICATION,
                BluetoothSocketException.L2CAP_INSUFFICIENT_AUTHORIZATION,
                BluetoothSocketException.L2CAP_INSUFFICIENT_ENCRYPTION,
                BluetoothSocketException.L2CAP_INSUFFICIENT_ENCRYPT_KEY_SIZE,
                BluetoothSocketException.L2CAP_NO_PSM_AVAILABLE,
                BluetoothSocketException.L2CAP_INVALID_PARAMETERS,
                BluetoothSocketException.L2CAP_UNACCEPTABLE_PARAMETERS,
            )

        // Act & Assert
        terminalCodes.forEach { code -> assertTrue(isTerminalL2capErrorCode(code), "code=$code") }
    }

    @Test
    fun nonTerminalErrorCodesAreClassifiedAsRetryable(): Unit {
        // Arrange: representative transient/unknown codes from the same skill-documented set.
        val transientCodes =
            listOf(
                BluetoothSocketException.UNSPECIFIED,
                BluetoothSocketException.RPC_FAILURE,
                BluetoothSocketException.SOCKET_CLOSED,
                BluetoothSocketException.SOCKET_CONNECTION_FAILURE,
                BluetoothSocketException.SOCKET_MANAGER_FAILURE,
                BluetoothSocketException.L2CAP_ACL_FAILURE,
                BluetoothSocketException.L2CAP_TIMEOUT,
                BluetoothSocketException.L2CAP_NO_RESOURCES,
                BluetoothSocketException.L2CAP_UNKNOWN,
            )

        // Act & Assert
        transientCodes.forEach { code -> assertFalse(isTerminalL2capErrorCode(code), "code=$code") }
    }
}
