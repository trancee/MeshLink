package ch.trancee.meshlink.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val TAG = "MainActivity"

/**
 * Android entry point for the MeshLink reference app.
 *
 * Requests Android 12+ (API 31+) runtime BLE permissions — BLUETOOTH_SCAN,
 * BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT — before composing the main [App] UI. While
 * permissions are pending or denied, [BlePermissionFallback] is shown instead.
 *
 * [appContext] is assigned from [applicationContext] in [onCreate] **before** [setContent] is
 * called, so [createPlatformMeshLink] can safely access it when [App] first composes
 * the [MeshController].
 *
 * Permission result is tracked as Compose state so the UI recomposes automatically when the
 * system permission dialog resolves.
 */
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions.values.all { it }
        Log.d(TAG, "BLE permission result: granted=$granted detail=$permissions")
        permissionsGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be set before setContent so createPlatformMeshLink can use it when App() composes.
        appContext = applicationContext

        val blePermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        // Short-circuit if permissions are already held (e.g. app re-launch after first grant).
        permissionsGranted = blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!permissionsGranted) {
            Log.d(TAG, "Requesting BLE runtime permissions")
            permissionLauncher.launch(blePermissions)
        } else {
            Log.d(TAG, "BLE permissions already granted — starting App directly")
        }

        setContent {
            if (permissionsGranted) {
                // Permissions are granted: compose the full app with real BLE transport.
                App()
            } else {
                // Permissions pending or denied: show informational fallback.
                // MeshLink is NOT created here, so the engine stays UNINITIALIZED.
                BlePermissionFallback()
            }
        }
    }
}

/**
 * Fallback composable shown when BLE runtime permissions have not yet been granted or were
 * denied by the user.
 *
 * Guides the user to grant the three required Bluetooth permissions. The engine is not
 * initialised at this stage — [App] (and therefore [MeshController]) is only composed after
 * [permissionsGranted] becomes `true`.
 */
@Composable
private fun BlePermissionFallback() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Bluetooth permissions are required to run MeshLink.\n\n" +
                    "Please grant BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, and " +
                    "BLUETOOTH_CONNECT when prompted.",
                textAlign = TextAlign.Center,
            )
        }
    }
}
