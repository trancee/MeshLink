package ch.trancee.meshlink.docs

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// The Android code of docs/how-to/request-permissions.md; ./gradlew checkDocRegions keeps the two equal.
// No Timeout rule: it runs the test on another thread, and an activity must be driven on the main looper.
@RunWith(RobolectricTestRunner::class)
class RequestPermissionsAndroidTest {
    @Test
    @Config(sdk = [37])
    fun android12AndLaterAsksForTheThreeBluetoothPermissions() {
        // Arrange
        val activity = Robolectric.buildActivity(ChatActivity::class.java).setup().get()

        // Act
        activity.askForBluetooth()

        // Assert
        assertEquals(
            listOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT),
            shadowOf(activity).lastRequestedPermission.requestedPermissions.toList(),
        )
    }

    @Test
    @Config(sdk = [26])
    fun olderAndroidAsksForPreciseLocation() {
        // Arrange
        val activity = Robolectric.buildActivity(ChatActivity::class.java).setup().get()

        // Act
        activity.askForBluetooth()

        // Assert
        assertEquals(
            listOf(ACCESS_FINE_LOCATION),
            shadowOf(activity).lastRequestedPermission.requestedPermissions.toList(),
        )
    }
}

class ChatActivity : ComponentActivity() {
    // region request-android
    private val bluetoothPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
        } else {
            arrayOf(ACCESS_FINE_LOCATION)
        }

    // MeshLink checks the permissions again when an activity resumes, so the callback has nothing to do.
    private val requestBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun askForBluetooth() = requestBluetooth.launch(bluetoothPermissions)
    // endregion
}
