package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
            val keychain = FakeKeychain()
            val port = ApplePlatformPort(bluetooth, keychain, dispatcher)
            val missing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.requirements.first() }

            // Act
            bluetooth.events.centralState(CbState.POWERED_ON)
            bluetooth.events.peripheralState(CbState.POWERED_ON)

            // Assert
            assertTrue(missing.isCompleted)
            assertSame(dispatcher, port.dispatcher)
            assertSame(assertIs<AppleRadio>(port.radio).delegates, bluetooth.delegates)
            val identity = assertNotNull(port.identityStore.loadOrCreate(SodiumCryptoProvider))
            assertContentEquals(identity.seed, keychain.added.single())
            assertSame(SystemClock, port.clock)
        }
}
