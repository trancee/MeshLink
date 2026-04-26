package ch.trancee.meshlink.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point for the MeshLink reference app.
 *
 * Delegates all UI to the shared [App] composable via Compose Multiplatform.
 * Uses [ComponentActivity] (from androidx.activity) so that lifecycle-aware
 * Compose APIs (LocalLifecycleOwner, viewModel(), etc.) work correctly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
