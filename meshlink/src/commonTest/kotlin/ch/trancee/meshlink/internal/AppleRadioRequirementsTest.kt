package ch.trancee.meshlink.internal

import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppleRadioRequirementsTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth)

    private fun TestScope.collect(): List<Set<Requirement>> {
        val seen = mutableListOf<Set<Requirement>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.requirements.toList(seen) }
        return seen
    }

    private fun report(
        central: Long,
        peripheral: Long = central,
    ) {
        bluetooth.events.centralState(central)
        bluetooth.events.peripheralState(peripheral)
    }

    @Test
    fun collectingOpensTheManagersOnceAndReportsNothingUntilBothKnowTheirState() =
        runTest {
            // Arrange
            val seen = collect()
            collect()

            // Act
            bluetooth.events.centralState(CbState.POWERED_ON)

            // Assert
            assertEquals(listOf("open"), bluetooth.calls)
            assertEquals(emptyList(), seen)
        }

    @Test
    fun bothManagersPoweredOnMissNothing() =
        runTest {
            // Arrange
            val seen = collect()

            // Act
            report(CbState.POWERED_ON)

            // Assert
            assertEquals(listOf(emptySet()), seen)
        }

    @Test
    fun eachManagerStateMapsToWhatTheRadioMisses() =
        runTest {
            // Arrange
            val seen = collect()
            val expected =
                listOf(
                    CbState.UNSUPPORTED to setOf(Requirement.Unsupported),
                    CbState.UNAUTHORIZED to setOf(Requirement.PermissionDenied(PermissionKind.Bluetooth)),
                    CbState.POWERED_OFF to setOf(Requirement.BluetoothOff),
                    CbState.POWERED_ON to emptySet(),
                    CbState.RESETTING to setOf(Requirement.BluetoothOff),
                )

            // Act
            val settled = expected.map { (state, _) -> report(state).let { seen.last() } }

            // Assert
            assertEquals(expected.map { it.second }, settled)
        }

    @Test
    fun theMissingRequirementsOfBothManagersAdd() =
        runTest {
            // Arrange
            val seen = collect()

            // Act
            report(central = CbState.POWERED_OFF, peripheral = CbState.UNAUTHORIZED)

            // Assert
            assertEquals(
                setOf(Requirement.BluetoothOff, Requirement.PermissionDenied(PermissionKind.Bluetooth)),
                seen.last(),
            )
        }

    @Test
    fun anUnknownStateBeforeTheUserAnsweredThePromptIsADeniedPermission() =
        runTest {
            // Arrange
            bluetooth.authorization = CbAuthorization.NOT_DETERMINED
            val seen = collect()

            // Act
            report(CbState.UNKNOWN)

            // Assert
            assertEquals(listOf(setOf(Requirement.PermissionDenied(PermissionKind.Bluetooth))), seen)
        }

    @Test
    fun anUnknownStateOnceAuthorizedReportsNothingYet() =
        runTest {
            // Arrange
            val seen = collect()

            // Act
            report(CbState.UNKNOWN)

            // Assert
            assertEquals(emptyList(), seen)
        }

    @Test
    fun anUnchangedStateReportsNothingNew() =
        runTest {
            // Arrange
            val seen = collect()
            report(CbState.POWERED_OFF)

            // Act
            report(CbState.RESETTING)

            // Assert
            assertEquals(listOf(setOf(Requirement.BluetoothOff)), seen)
        }
}
