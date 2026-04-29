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
import ch.trancee.meshlink.api.MeshLinkConfig

class MainActivity : ComponentActivity() {
    private lateinit var meshLink: MeshLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = MeshLinkConfig {
            appId = "com.example.myapp"  // isolates your mesh from other MeshLink apps
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
    meshLink.start()

    // Observe peer discovery
    lifecycleScope.launch {
        meshLink.peerEvents.collect { events ->
            for (event in events) {
                when (event) {
                    is PeerEvent.Found -> println("Discovered: ${event.peerId}")
                    is PeerEvent.Lost -> println("Lost: ${event.peerId}")
                    is PeerEvent.StateChanged -> println("${event.peerId} → ${event.state}")
                    else -> {}
                }
            }
        }
    }
}
```

## 6. Send a message

Once you see a peer discovered, send them a message:

```kotlin
lifecycleScope.launch {
    val result = meshLink.send(
        recipient = discoveredPeerId,
        data = "Hello from Android!".encodeToByteArray()
    )
    when (result) {
        is SendResult.Queued -> println("Message queued for delivery")
        is SendResult.Failed -> println("Send failed: ${result.reason}")
    }
}
```

## 7. Receive messages

```kotlin
lifecycleScope.launch {
    meshLink.incomingMessages.collect { message ->
        val text = message.data.decodeToString()
        println("From ${message.sender}: $text")
    }
}
```

## 8. Stop cleanly

```kotlin
override fun onDestroy() {
    meshLink.stop()  // immediate teardown
    // or: meshLink.stop(timeout = 5.seconds)  // graceful drain
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
