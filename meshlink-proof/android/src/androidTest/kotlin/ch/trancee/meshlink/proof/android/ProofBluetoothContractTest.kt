package ch.trancee.meshlink.proof.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofBluetoothContractTest {
    @Test
    fun evaluate_returns_ready_when_manager_adapter_and_enabled_are_all_available() {
        val readiness =
            ProofBluetoothContract.evaluate(
                bluetoothManagerAvailable = true,
                bluetoothAdapterAvailable = true,
                bluetoothEnabled = true,
            )

        assertTrue(readiness.ready)
        assertEquals(null, readiness.reason)
    }

    @Test
    fun evaluate_reports_missing_manager_before_adapter_or_enabled_state() {
        val readiness =
            ProofBluetoothContract.evaluate(
                bluetoothManagerAvailable = false,
                bluetoothAdapterAvailable = true,
                bluetoothEnabled = true,
            )

        assertFalse(readiness.ready)
        assertEquals("BluetoothManager is unavailable", readiness.reason)
    }

    @Test
    fun evaluate_reports_disabled_bluetooth() {
        val readiness =
            ProofBluetoothContract.evaluate(
                bluetoothManagerAvailable = true,
                bluetoothAdapterAvailable = true,
                bluetoothEnabled = false,
            )

        assertFalse(readiness.ready)
        assertEquals("Bluetooth is turned off", readiness.reason)
    }
}
