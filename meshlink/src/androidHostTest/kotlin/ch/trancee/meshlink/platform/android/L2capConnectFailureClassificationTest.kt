package ch.trancee.meshlink.platform.android

import java.io.IOException
import kotlin.test.Test
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
}
