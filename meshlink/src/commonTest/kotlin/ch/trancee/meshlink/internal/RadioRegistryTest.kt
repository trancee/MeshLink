package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RadioRegistryTest {
    private val port = ApplePlatformPort(FakeCoreBluetooth(), FakeKeychain(), StandardTestDispatcher())
    private val holder = Any()

    @AfterTest
    fun freeThePort() = RadioRegistry.release(port, holder)

    @Test
    fun aClaimOnAHeldRadioFailsAndLeavesItsHolder() =
        runTest {
            // Arrange
            RadioRegistry.tryAcquire(port, holder)

            // Act
            val claimed = RadioRegistry.tryAcquire(port, Any())

            // Assert
            assertFalse(claimed)
            assertSame(holder, RadioRegistry.holder(port).first())
        }

    @Test
    fun aReleaseByANodeThatDoesNotHoldTheRadioLeavesItsHolder() =
        runTest {
            // Arrange
            RadioRegistry.tryAcquire(port, holder)

            // Act
            RadioRegistry.release(port, Any())

            // Assert
            assertSame(holder, RadioRegistry.holder(port).first())
        }
}
