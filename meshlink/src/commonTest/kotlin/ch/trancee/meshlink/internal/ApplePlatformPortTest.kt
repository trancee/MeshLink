package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.Severity
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            val port = ApplePlatformPort(bluetooth, keychain, dispatcher, OS_VERSION, FakeAppLifecycle()) { _, _, _ -> }
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
            assertEquals("iOS", port.platformName)
            assertEquals(OS_VERSION, port.osVersion)
        }

    @Test
    fun thePortReportsTheAppsBackgroundAndSuspensionsAndNeverDoze() =
        runTest {
            // Arrange
            val lifecycle = FakeAppLifecycle { testScheduler.currentTime }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bluetooth = FakeCoreBluetooth()
            val port = ApplePlatformPort(bluetooth, FakeKeychain(), dispatcher, OS_VERSION, lifecycle) { _, _, _ -> }
            val suspended = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                port.suspensions.collect { suspended += it }
            }

            // Act
            lifecycle.background.value = true
            lifecycle.slept = SUSPENDED_MILLIS
            advanceTimeBy(CHECK_MILLIS + 1)

            // Assert
            assertTrue(port.background.first())
            assertFalse(port.doze.first())
            assertEquals(listOf(SUSPENDED_MILLIS), suspended)
        }

    @Test
    fun eachSeverityIsWrittenToTheOsLogAtItsType() {
        // Arrange
        val written = mutableListOf<Triple<UByte, String, String>>()
        val dispatcher = StandardTestDispatcher()
        val port =
            ApplePlatformPort(FakeCoreBluetooth(), FakeKeychain(), dispatcher, OS_VERSION, FakeAppLifecycle()) {
                type,
                shown,
                hidden,
                ->
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
        const val OS_VERSION = "18.2.0"
        const val CHECK_MILLIS = 10_000L
        const val SUSPENDED_MILLIS = 45_000L
        const val OS_LOG_TYPE_DEFAULT: UByte = 0x00u
        const val OS_LOG_TYPE_INFO: UByte = 0x01u
        const val OS_LOG_TYPE_DEBUG: UByte = 0x02u
        const val OS_LOG_TYPE_ERROR: UByte = 0x10u
    }
}

/** An [AppLifecycle] whose background state tests set, on a clock that runs [slept] ahead of the test scheduler. */
internal class FakeAppLifecycle(
    private val now: () -> Long = { 0 },
) : AppLifecycle {
    override val background: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** How long the app slept so far, in ms. */
    var slept: Long = 0

    override fun continuousMillis(): Long = now() + slept
}
