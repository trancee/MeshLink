# Tutorial: Your First Android Integration

> **Time:** ~15 minutes  
> **What you'll build:** An Android app that discovers a nearby MeshLink peer and sends a message.  
> **Prerequisites:** Android Studio, a physical Android device (BLE required), Kotlin knowledge.

---

## 1. Add the dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ch.trancee:meshlink:0.1.0")
}
```

Minimum SDK: 29 (Android 10).

## 2. Declare permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

No `ACCESS_FINE_LOCATION` needed — the `neverForLocation` flag tells the system we don't use scan results for positioning.

## 3. Create a MeshLink instance

```kotlin
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.meshLinkConfig

class MainActivity : ComponentActivity() {
    private lateinit var meshLink: MeshLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = meshLinkConfig("com.example.myapp") {
            // appId isolates your mesh from other MeshLink apps
        }
        meshLink = MeshLink.createAndroid(this, config)
    }
}
```

## 4. Request runtime permissions

Before starting MeshLink, request BLE permissions at runtime (Android 12+):

```kotlin
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { grants ->
    if (grants.values.all { it }) {
        startMesh()
    }
}

private fun requestPermissions() {
    permissionLauncher.launch(arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ))
}
```

## 5. Start the mesh and observe peers

```kotlin
private fun startMesh() {
    lifecycleScope.launch {
        meshLink.start()
    }

    // Observe peer discovery
    lifecycleScope.launch {
        meshLink.peers.collect { event ->
            when (event) {
                is PeerEvent.Found -> println("Discovered: ${event.id.toHex()}")
                is PeerEvent.Lost -> println("Lost: ${event.id.toHex()}")
                is PeerEvent.StateChanged -> println("${event.id.toHex()} → ${event.state}")
            }
        }
    }
}

// Helper to display peer IDs
private fun ByteArray.toHex(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
```

## 6. Send a message

Once you see a peer discovered, send them a message:

```kotlin
lifecycleScope.launch {
    meshLink.send(
        recipient = discoveredPeerId,  // ByteArray from PeerEvent.Found
        payload = "Hello from Android!".encodeToByteArray(),
    )
}
```

The call suspends until the message is queued for delivery. E2E encryption (Noise K) is applied automatically.

## 7. Receive messages

```kotlin
lifecycleScope.launch {
    meshLink.messages.collect { message ->
        val text = message.payload.decodeToString()
        println("From ${message.senderId.toHex()}: $text")
    }
}
```

## 8. Stop cleanly

```kotlin
override fun onDestroy() {
    lifecycleScope.launch {
        meshLink.stop()  // immediate teardown
        // or: meshLink.stop(timeout = 5.seconds)  // graceful drain
    }
    super.onDestroy()
}
```

## What you've accomplished

You now have an Android app that:
- Advertises its presence over BLE
- Discovers nearby MeshLink peers
- Sends encrypted messages (Noise K E2E encryption happens automatically)
- Receives messages from peers
- Operates without internet, servers, or user accounts

## Next steps

- [Your First iOS Integration](first-ios-integration.md) — get a second device talking back
- [How to Consume Diagnostic Events](../how-to/consume-diagnostics.md) — see what's happening inside the protocol
- [How to Configure Power Tiers](../how-to/configure-power-tiers.md) — tune for battery life
