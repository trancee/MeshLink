package io.meshlink.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            val denied = grants.filter { !it.value }.keys.map { it.substringAfterLast('.') }
            Log.w("MeshLink.BLE", "⚠️ Permissions denied: $denied")
            Toast.makeText(this, "Bluetooth & Location permissions required", Toast.LENGTH_LONG).show()
        }
        if (allGranted) {
            // Permissions OK — now check Location Services
            checkLocationServicesAndProceed()
        } else {
            onPermissionResult?.invoke(false)
            onPermissionResult = null
        }
    }

    /** Request BLE + Location permissions, check Location Services, then invoke [onResult]. */
    fun ensurePermissions(onResult: (granted: Boolean) -> Unit) {
        onPermissionResult = onResult

        // Log current permission status
        val statuses = requiredPermissions.associate { perm ->
            perm.substringAfterLast('.') to
                (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
        }
        Log.d("MeshLink.BLE", "Permission check: $statuses")

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Log.d("MeshLink.BLE", "✅ All permissions granted")
            checkLocationServicesAndProceed()
        } else {
            Log.d("MeshLink.BLE", "Requesting permissions: ${missing.map { it.substringAfterLast('.') }}")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkLocationServicesAndProceed() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsOn && !networkOn) {
            Log.w("MeshLink.BLE", "⚠️ Location Services are OFF — BLE scanning requires them")
            Toast.makeText(this, "Please enable Location Services for BLE scanning", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            // Don't call onResult yet — user needs to enable location and tap Start again
            onPermissionResult?.invoke(false)
            onPermissionResult = null
        } else {
            Log.d("MeshLink.BLE", "✅ Location Services ON (gps=$gpsOn, network=$networkOn)")
            onPermissionResult?.invoke(true)
            onPermissionResult = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeshLinkApp()
            }
        }
    }
}

// ── Navigation tabs ──

private enum class Screen(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Default.Email),
    Mesh("Mesh", Icons.Default.Share),
    Diagnostics("Diagnostics", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * Root composable with a bottom navigation bar switching between
 * Chat, Mesh Visualizer, and Settings screens.
 */
@Composable
fun MeshLinkApp(viewModel: MeshLinkViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // Apply bottom-bar insets so sub-screen content doesn't hide behind the nav bar.
        Box(
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (Screen.entries[selectedTab]) {
                Screen.Chat -> ChatScreen(viewModel)
                Screen.Mesh -> MeshVisualizerScreen(viewModel)
                Screen.Diagnostics -> DiagnosticsScreen(viewModel)
                Screen.Settings -> SettingsScreen(viewModel)
            }
        }
    }
}

// ── Chat screen (original content) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MeshLinkViewModel) {
    val health by viewModel.health.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val peers by viewModel.discoveredPeers.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity

    var selectedPeerIndex by rememberSaveable { mutableIntStateOf(0) }
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
                onClick = {
                    if (isRunning) {
                        viewModel.stopMesh()
                    } else {
                        activity?.ensurePermissions { granted ->
                            if (granted) viewModel.startMesh()
                        } ?: viewModel.startMesh()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isRunning) "Stop Mesh" else "Start Mesh")
            }

            // ── Recipient selector ──
            val peerOptions = listOf("📡 Broadcast") + peers.map { it.id.take(12) + "…" }
            var dropdownExpanded by rememberSaveable { mutableStateOf(false) }
            // Reset selection if peers list changed and index is out of bounds
            if (selectedPeerIndex >= peerOptions.size) selectedPeerIndex = 0

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = peerOptions[selectedPeerIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Recipient") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    peerOptions.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedPeerIndex = index
                                dropdownExpanded = false
                            },
                        )
                    }
                }
            }
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
                        if (selectedPeerIndex == 0) {
                            viewModel.broadcastMessage(message)
                        } else {
                            viewModel.sendMessage(peers[selectedPeerIndex - 1].id, message)
                        }
                        message = ""
                    },
                    enabled = isRunning && message.isNotBlank()
                ) {
                    Text(if (selectedPeerIndex == 0) "Broadcast" else "Send")
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
