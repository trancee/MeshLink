package ch.trancee.meshlink.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.ReceivedMessage
import kotlinx.coroutines.launch

/**
 * Chat screen composable.
 *
 * Displays the current [MeshLinkState] and connected peer count in a header, a scrollable list of
 * [ReceivedMessage]s in the body, and a text input row at the bottom. The send action is wired to
 * [MeshController.broadcast], which UTF-8 encodes the text and sends it to all reachable peers.
 *
 * The send button is disabled when the engine is not [MeshLinkState.RUNNING] or when the text
 * field is blank.
 */
@Composable
fun ChatScreen(controller: MeshController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val messages by controller.messages.collectAsState()
    val health by controller.healthSnapshot.collectAsState()

    var messageText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message whenever the list grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Connection header ──────────────────────────────────────────────
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "State: ${state.name}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "Peers: ${health?.connectedPeers ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // ── Message list ───────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(
                    text = "No messages yet",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id.bytes.contentHashCode() }) { msg ->
                        MessageItem(msg)
                    }
                }
            }
        }

        // ── Message input row ──────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = state == MeshLinkState.RUNNING && messageText.isNotBlank(),
                onClick = {
                    val text = messageText.trim()
                    messageText = ""
                    scope.launch { controller.broadcast(text) }
                },
            ) {
                Text("Send")
            }
        }
    }
}

// ── MessageItem ───────────────────────────────────────────────────────────────

/**
 * Renders a single [ReceivedMessage] row showing:
 * - Hex-truncated sender ID (first 6 bytes = 12 hex chars)
 * - Payload size and relative timestamp
 * - Decoded payload text (best-effort UTF-8)
 */
@Composable
private fun MessageItem(msg: ReceivedMessage, modifier: Modifier = Modifier) {
    val senderHex = msg.senderId
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        .take(12)   // 6 bytes → 12 hex chars; visually distinct without being unwieldy

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = senderHex,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${msg.payload.size}B · +${msg.receivedAtMillis % 100_000}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = msg.payload.decodeToString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}
