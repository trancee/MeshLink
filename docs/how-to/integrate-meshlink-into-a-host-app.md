# How to integrate MeshLink into a host app

This guide shows you how to bootstrap MeshLink in an app, start it safely, collect its event streams, send payloads, and handle trust resets.

This is a task guide. It assumes you already know why you want MeshLink.

If you still need to wire the library into your Android or iOS build, start with [How to add MeshLink to your app](add-meshlink-to-your-app.md).

If you also want the architecture and operational guardrails for a production-shaped app integration, use [How to structure a robust MeshLink integration](structure-a-robust-meshlink-integration.md).

For a first hands-on lesson, start with [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md).

## 1. Choose one `appId` per mesh domain

Create one `MeshLinkConfig` for all peers that should interoperate in the same mesh.

```kotlin
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.time.Duration.Companion.seconds

fun meshLinkConfiguration(): MeshLinkConfig {
    return meshLinkConfig {
        appId = "com.example.chat.prod"
        regulatoryRegion = RegulatoryRegion.DEFAULT
        powerMode = PowerMode.Automatic
        deliveryRetryDeadline = 15.seconds
    }
}
```

Use a different `appId` for test, staging, or proof work when you do not want devices to discover each other.

## 2. Create the runtime on Android

On Android, create the runtime with an application `Context`.

```kotlin
import android.content.Context
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkApi

fun createAndroidRuntime(context: Context): MeshLinkApi {
    return MeshLink.create(
        config = meshLinkConfiguration(),
        context = context.applicationContext,
    )
}
```

Do this once for the app-owned MeshLink service or controller that will manage the runtime.

## 3. Create the runtime on iOS

On iOS, install the crypto bridge during app startup, then create the runtime with the same shared config.

```swift
import MeshLink
import SwiftUI

@main
struct ChatApp: App {
    let meshLink: MeshLinkApi

    init() {
        installMeshLinkCrypto()

        let config = MeshLinkConfigKt.meshLinkConfig { builder in
            builder.appId = "com.example.chat.prod"
            builder.regulatoryRegion = RegulatoryRegion.default_
            builder.powerMode = PowerMode.Automatic.shared
        }

        meshLink = MeshLink.shared.create(config: config)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

Your `installMeshLinkCrypto()` wrapper should call `IosCryptoBridge.shared.install(...)` with app-owned CryptoKit-backed callbacks.

If you need the iPhone-hosted GATT-notify side bearer, install the optional `IosBleTransportBridge` during startup as well.

## 4. Start MeshLink from one app-owned lifecycle boundary

Start MeshLink from a single owner such as a service, app controller, or view model.

```kotlin
import ch.trancee.meshlink.api.MeshLinkApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MeshLinkController(
    private val meshLink: MeshLinkApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            val result = meshLink.start()
            println("mesh.start() -> $result")
        }
    }

    fun stop() {
        scope.launch {
            val result = meshLink.stop()
            println("mesh.stop() -> $result")
        }
    }
}
```

Do not create a fresh runtime for every send.

Repeated lifecycle calls do not throw. They return the matching `Already*` result
variant so your controller can stay idempotent.

Before you call `start()`, make sure the platform permission work is already
finished. On Android, request the runtime Bluetooth permissions first. On iOS,
ship the Bluetooth usage description and handle the first-run prompt. If startup
or discovery stalls, follow [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

Also bind your long-lived collectors before you call `start()` when you need full
session visibility. `peerEvents`, `diagnosticEvents`, and `messages` are hot,
non-replaying streams, so collectors attached after startup can miss early events.

## 5. Collect state, peer, diagnostic, and message streams

Collect the four public streams and fan their data into your UI, logs, or app state.
For full-session visibility, attach these collectors before you call `start()`.

```kotlin
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun bindMeshLinkFlows(meshLink: MeshLinkApi, scope: CoroutineScope) {
    scope.launch {
        meshLink.state.collect { state ->
            println("state = $state")
        }
    }

    scope.launch {
        meshLink.peerEvents.collect { event: PeerEvent ->
            println("peer event = $event")
        }
    }

    scope.launch {
        meshLink.diagnosticEvents.collect { event: DiagnosticEvent ->
            println("diagnostic = ${event.code} ${event.stage}")
        }
    }

    scope.launch {
        meshLink.messages.collect { message: InboundMessage ->
            println("message = ${message.originPeerId} bytes=${message.payload.size}")
        }
    }
}
```

Use `peerEvents` to drive peer presence in your UI. Use `diagnosticEvents` for operator visibility and troubleshooting. `InboundMessage.receivedAtEpochMillis` records when MeshLink delivered the message to your app, so you can reuse it for ordering, logging, or UI timestamps.

## 6. Send a payload

Keep the discovered `PeerId` you want to target and send bytes.

```kotlin
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult

suspend fun sendChatMessage(meshLink: MeshLinkApi, peerId: PeerId, text: String): SendResult {
    return meshLink.send(
        peerId = peerId,
        payload = text.encodeToByteArray(),
        priority = DeliveryPriority.NORMAL,
    )
}
```

Treat `SendResult.NotSent(...)` as a normal delivery outcome that needs app logic, not exception handling.

## 7. Update battery state when you want automatic power policy to react

If your app is feeding battery snapshots into MeshLink, call `updateBattery()` with normalized values.

```kotlin
meshLink.updateBattery(level = 0.42f, isCharging = false)
```

This affects the effective policy when `powerMode` is `Automatic`.

## 8. Reset trust deliberately

If the user chooses to forget a peer, call `forgetPeer()` explicitly.

```kotlin
val result = meshLink.forgetPeer(peerId)
println("forgetPeer() -> $result")
```

Use this for deliberate trust reset flows. Do not silently forget peers in normal transport churn.

## 9. Handle iOS bridge installation explicitly

For iOS, keep the bridge installation code in app-owned startup code.

- `IosCryptoBridge` is required before any real iOS runtime path needs cryptography.
- `IosBleTransportBridge` is optional and only needed when you want the iPhone-hosted GATT-notify bearer path.

Do not spread bridge installation across multiple screens or feature modules.

## 10. Link your UI to the correct stream

A reliable default mapping is:

- app lifecycle state → `state`
- peer list / availability → `peerEvents`
- operator logs / support tooling → `diagnosticEvents`
- user-visible inbound content → `messages`

If you need application-level delivery confirmation, implement your own application receipt message on top of MeshLink rather than treating `SendResult.Sent` as a read receipt.

## Related docs

- [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md)
- [How to structure a robust MeshLink integration](structure-a-robust-meshlink-integration.md)
- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
- [The trust model](../explanation/trust-model.md)
- [The peer lifecycle model](../explanation/peer-lifecycle.md)
- [Power management](../explanation/power-management.md)
