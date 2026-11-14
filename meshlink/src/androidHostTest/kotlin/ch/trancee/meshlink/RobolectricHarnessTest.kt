package ch.trancee.meshlink

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 37])
class RobolectricHarnessTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun bluetoothManagerReturnsAnAdapter() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(BluetoothManager::class.java)

        // Act
        val adapter = manager.adapter

        // Assert
        assertNotNull(adapter)
    }
}
