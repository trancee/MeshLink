package ch.trancee.meshlink.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Root composable for the MeshLink reference app.
 *
 * This is the CMP entry point shared between Android (via MainActivity) and iOS (via
 * MainViewController). T01 provides a minimal shell that proves CMP compilation works;
 * subsequent tasks (T02–T05) will replace the placeholder with 4 functional screens and bottom
 * navigation driven by MeshController.
 */
@Composable
fun App() {
    MaterialTheme { Text("MeshLink Reference App") }
}
