package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApplePlatformPortTest {
    @Test
    fun thePortRunsOnItsDispatcherAndReportsWhatItsRadioMisses() =
        runTest {
            // Arrange
            val bluetooth = FakeCoreBluetooth()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val port = ApplePlatformPort(bluetooth, dispatcher)
            val missing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.requirements.first() }

            // Act
            bluetooth.events.centralState(CbState.POWERED_ON)
            bluetooth.events.peripheralState(CbState.POWERED_ON)

            // Assert
            assertTrue(missing.isCompleted)
            assertSame(dispatcher, port.dispatcher)
            assertSame(assertIs<AppleRadio>(port.radio).delegates, bluetooth.delegates)
            assertIs<InMemoryIdentityStore>(port.identityStore)
            assertSame(SystemClock, port.clock)
        }
}
