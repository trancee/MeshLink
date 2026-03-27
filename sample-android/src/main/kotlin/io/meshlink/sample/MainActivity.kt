package io.meshlink.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeshLinkScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshLinkScreen(viewModel: MeshLinkViewModel = viewModel()) {
    val health by viewModel.health.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    var recipientId by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MeshLink Sample") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Health status ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mesh Health", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Connected peers: ${health.connectedPeers}")
                    Text("Reachable peers: ${health.reachablePeers}")
                    Text("Power mode: ${health.powerMode}")
                    Text("Buffer usage: ${health.bufferUtilizationPercent}%")
                    Text("Active transfers: ${health.activeTransfers}")
                }
            }

            // ── Start / Stop toggle ──
            Button(
                onClick = { if (isRunning) viewModel.stopMesh() else viewModel.startMesh() },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isRunning) "Stop Mesh" else "Start Mesh")
            }

            // ── Send message ──
            OutlinedTextField(
                value = recipientId,
                onValueChange = { recipientId = it },
                label = { Text("Recipient ID (hex)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        viewModel.sendMessage(recipientId, message)
                        message = ""
                    },
                    enabled = isRunning && recipientId.isNotBlank() && message.isNotBlank()
                ) {
                    Text("Send")
                }
            }

            HorizontalDivider()

            // ── Event log ──
            Text("Event Log", style = MaterialTheme.typography.titleMedium)

            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState
            ) {
                items(logs) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    listState.animateScrollToItem(logs.size - 1)
                }
            }
        }
    }
}
