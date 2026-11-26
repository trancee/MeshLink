package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.Severity
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
            val port = ApplePlatformPort(bluetooth, keychain, dispatcher) { _, _, _ -> }
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

    @Test
    fun eachSeverityIsWrittenToTheOsLogAtItsType() {
        // Arrange
        val written = mutableListOf<Triple<UByte, String, String>>()
        val port =
            ApplePlatformPort(FakeCoreBluetooth(), FakeKeychain(), StandardTestDispatcher()) { type, shown, hidden ->
                written += Triple(type, shown, hidden)
            }

        // Act
        Severity.entries.forEach { port.log.write(it, it.name, "peer=ab12") }

        // Assert
        val expected =
            listOf(
                Triple(OS_LOG_TYPE_DEBUG, "Debug", "peer=ab12"),
                Triple(OS_LOG_TYPE_INFO, "Info", "peer=ab12"),
                Triple(OS_LOG_TYPE_DEFAULT, "Warning", "peer=ab12"),
                Triple(OS_LOG_TYPE_ERROR, "Error", "peer=ab12"),
            )
        assertEquals(expected, written)
    }

    private companion object {
        const val OS_LOG_TYPE_DEFAULT: UByte = 0x00u
        const val OS_LOG_TYPE_INFO: UByte = 0x01u
        const val OS_LOG_TYPE_DEBUG: UByte = 0x02u
        const val OS_LOG_TYPE_ERROR: UByte = 0x10u
    }
}
