package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import ch.trancee.meshlink.platform.android.scan.BLUETOOTH_STATE_CHANGE_RESTART_DEBOUNCE_MILLIS
import ch.trancee.meshlink.platform.android.scan.BluetoothStateChangeDebouncer
import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothStateChangeSupportTest {
    @Test
    fun stateOnSchedulesADebouncedRestart(): Unit {
        // Arrange
        val fixture = BluetoothStateChangeDebouncerFixture()

        // Act
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_ON) { fixture.restartCalls += 1 }

        // Assert
        assertEquals(1, fixture.scheduledDelaysMillis.size)
        assertEquals(
            BLUETOOTH_STATE_CHANGE_RESTART_DEBOUNCE_MILLIS,
            fixture.scheduledDelaysMillis.single(),
        )
        assertEquals(0, fixture.restartCalls)
        fixture.runScheduledRestarts()
        assertEquals(1, fixture.restartCalls)
    }

    @Test
    fun nonOnStatesDoNotScheduleARestart(): Unit {
        // Arrange
        val fixture = BluetoothStateChangeDebouncerFixture()

        // Act
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_OFF) { fixture.restartCalls += 1 }
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_TURNING_ON) {
            fixture.restartCalls += 1
        }
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_TURNING_OFF) {
            fixture.restartCalls += 1
        }
        fixture.debouncer.onStateChanged(BluetoothAdapter.ERROR) { fixture.restartCalls += 1 }

        // Assert
        assertEquals(0, fixture.scheduledDelaysMillis.size)
    }

    @Test
    fun rapidTogglingCollapsesIntoASingleRestartForTheLastToggle(): Unit {
        // Arrange
        val fixture = BluetoothStateChangeDebouncerFixture()

        // Act: three STATE_ON transitions in quick succession (e.g. flaky adapter reporting)
        // schedule three restarts, but only the last one should still fire once all of them run.
        repeat(3) {
            fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_ON) {
                fixture.restartCalls += 1
            }
        }
        fixture.runScheduledRestarts()

        // Assert
        assertEquals(3, fixture.scheduledDelaysMillis.size)
        assertEquals(1, fixture.restartCalls)
    }

    @Test
    fun aFreshStateOnAfterARestartAlreadyFiredSchedulesAnotherRestart(): Unit {
        // Arrange
        val fixture = BluetoothStateChangeDebouncerFixture()
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_ON) { fixture.restartCalls += 1 }
        fixture.runScheduledRestarts()
        assertEquals(1, fixture.restartCalls)

        // Act
        fixture.debouncer.onStateChanged(BluetoothAdapter.STATE_ON) { fixture.restartCalls += 1 }
        fixture.runScheduledRestarts()

        // Assert
        assertEquals(2, fixture.restartCalls)
    }
}

private class BluetoothStateChangeDebouncerFixture {
    val scheduledDelaysMillis = mutableListOf<Long>()
    var restartCalls: Int = 0
    private val pendingRestarts = mutableListOf<() -> Unit>()

    val debouncer =
        BluetoothStateChangeDebouncer(
            scheduleRestart = { delayMillis, restart ->
                scheduledDelaysMillis += delayMillis
                pendingRestarts += restart
            },
            log = {},
        )

    fun runScheduledRestarts(): Unit {
        val restarts = pendingRestarts.toList()
        pendingRestarts.clear()
        restarts.forEach { it() }
    }
}
