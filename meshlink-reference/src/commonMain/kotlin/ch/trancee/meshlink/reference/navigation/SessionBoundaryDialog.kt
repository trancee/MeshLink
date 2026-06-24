package ch.trancee.meshlink.reference.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun SessionBoundaryDialog(
    title: String,
    body: String,
    exportLabel: String,
    continueLabel: String,
    onExportAndContinue: () -> Unit,
    onContinueWithoutExport: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onExportAndContinue) { Text(exportLabel) } },
        dismissButton = { TextButton(onClick = onContinueWithoutExport) { Text(continueLabel) } },
    )
}
