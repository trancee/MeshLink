# How to integrate MeshLink into a host app

This guide shows you how to bootstrap MeshLink in an app, start it safely,
collect its public streams, send payloads, and handle trust resets.

By the end, you should have:

- one shared `MeshLinkConfig`
- one app-owned runtime on Android or iOS
- long-lived collectors for state, peers, diagnostics, and messages
- a safe start and stop boundary
- one working send path and one deliberate trust-reset path

If you still need to wire the library into your Android or iOS build, start
with [How to add MeshLink to your app](add-meshlink-to-your-app.md).
If you also want the architecture and guardrails for a production-shaped
integration, use
[How to structure a robust MeshLink integration](structure-a-robust-meshlink-integration.md).
For a first hands-on lesson, use
[Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md).

## Integration path at a glance

```mermaid
%%{init: {"theme": "base", "themeVariables": {"background": "#ffffff", "primaryColor": "#E8F1FF", "primaryBorderColor": "#3A7BD5", "primaryTextColor": "#102A43", "lineColor": "#6B7C93", "secondaryColor": "#EEF7EE", "tertiaryColor": "#FFF3E6", "clusterBkg": "#F8FAFC", "clusterBorder": "#B8C4D6", "fontFamily": "Inter, ui-sans-serif, system-ui"}}}%%
flowchart LR
    Config["Build one shared MeshLinkConfig"] --> Create["Create one app-owned runtime"]
    Create --> Bind["Bind long-lived collectors"]
    Bind --> Ready["Clear platform readiness"]
    Ready --> Start["Start the runtime"]
    Start --> Use["Send, receive, inspect diagnostics"]
    Use --> Stop["Stop deliberately when the owner ends"]

    classDef step fill:#E8F1FF,stroke:#3A7BD5,color:#102A43,stroke-width:1.5px;
    classDef action fill:#EEF7EE,stroke:#4D9F70,color:#14361F,stroke-width:1.5px;
    class Config,Create,Bind step;
    class Ready,Start,Use,Stop action;
```

Use this order as the default integration shape. The most common failure mode is
creating the runtime too late, binding collectors too narrowly, or treating
`send()` as if it can stand in for a long-lived runtime owner.

## 1. Choose one `appId` per mesh domain

Create one `MeshLinkConfig` for all peers that should interoperate in the same
mesh.

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

Use a different `appId` for test, staging, or proof work when you do not want
devices to discover each other.

## 2. Create the runtime on Android

On Android, create the runtime directly from an application `Context`:

```kotlin
import android.content.Context
import ch.trancee.meshlink.api.android.meshLink
import ch.trancee.meshlink.api.MeshLink

fun createAndroidRuntime(context: Context): MeshLink {
    return meshLink(
        config = meshLinkConfiguration(),
        context = context.applicationContext,
    )
}
```

Do this once for the app-owned MeshLink service or controller that will manage
the runtime.

Only reach for the two-step `meshLinkBootstrap(context)` + `meshLink(config,
bootstrap)` form if you need to construct the bootstrap handle ahead of the
runtime itself (for example, threading it through a dependency-injection
graph). Both forms produce an identical runtime.

## 3. Create the runtime on iOS

On iOS, install the crypto bridge during app startup, then create the runtime
with the same shared config.

```swift
import MeshLink
import SwiftUI

@main
struct ChatApp: App {
    let api: MeshLink.MeshLinkRuntime

    init() {
        installMeshLinkCrypto()

        let config = meshLinkConfig { builder in
            builder.appId = "com.example.chat.prod"
        }

        api = meshLink(config: config)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

Your `installMeshLinkCrypto()` wrapper should call
`CryptoBridge.shared.install(callbacks: ...)` with app-owned crypto
callbacks.

If you need the iPhone-hosted GATT-notify bearer, install the optional
`BleTransportBridge` during startup as well. Prefer `installData(...)` when
that path can work directly with Swift `Data` or `NSData`, because it avoids an
extra per-byte bridge copy back into Kotlin.

For the full Swift-facing bridge details, use
[How to use MeshLink from Swift](use-meshlink-from-swift.md).

## 4. Attach long-lived collectors before `start()`

If you need full-session visibility, attach your long-lived collectors before
you call `start()`.

```kotlin
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun bindMeshLinkFlows(meshLink: MeshLink, scope: CoroutineScope) {
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

Recommended responsibilities:

- `state` → runtime lifecycle state
- `peerEvents` → peer presence and connectivity
- `diagnosticEvents` → logs, operator tooling, troubleshooting
- `messages` → inbound application payloads

`InboundMessage.receivedAtEpochMillis` records when MeshLink delivered the
message to your app, so you can reuse it for ordering, logging, or UI
timestamps.

## 5. Start and stop from one app-owned lifecycle boundary

Create one owner such as a service, app controller, or view model. Bind the
flows once from that owner, then start and stop the runtime there.

```kotlin
import ch.trancee.meshlink.api.MeshLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MeshLinkController(
    private val meshLink: MeshLink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        bindMeshLinkFlows(meshLink, scope)
    }

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

Repeated lifecycle calls do not throw. They return the matching `Already*`
result variant so your controller can stay idempotent.

Before you call `start()`, make sure platform permission work is already
finished. On Android, request the runtime Bluetooth permissions first. On iOS,
ship the Bluetooth usage description and handle the first-run prompt. If
startup or discovery stalls, use
[How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

## 6. Send a payload

Keep the discovered `PeerId` you want to target and send bytes.

```kotlin
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult

suspend fun sendChatMessage(meshLink: MeshLink, peerId: PeerId, text: String): SendResult {
    return meshLink.send(
        peerId = peerId,
        payload = text.encodeToByteArray(),
        priority = DeliveryPriority.NORMAL,
    )
}
```

Treat `SendResult.NotSent(...)` as a normal delivery outcome that needs app
logic, not exception handling.

## 7. Feed battery state when you use automatic power mode

If your app is feeding battery snapshots into MeshLink, call `updateBattery()`
with normalized values.

```kotlin
meshLink.updateBattery(
    snapshot = BatterySnapshot(level = 0.42f, isCharging = false),
)
```

This affects the effective policy only when `powerMode` is `Automatic`.

## 8. Reset trust deliberately

If the user chooses to forget a peer, call `forgetPeer()` explicitly.

```kotlin
val result = meshLink.forgetPeer(peerId)
println("forgetPeer() -> $result")
```

Use this for deliberate trust-reset flows. Do not silently forget peers during
ordinary transport churn.

## 9. Keep the stream-to-UI mapping clear

A good default mapping is:

- app lifecycle state → `state`
- peer list or availability → `peerEvents`
- operator logs or support tooling → `diagnosticEvents`
- user-visible inbound content → `messages`

If you need application-level delivery confirmation, implement your own receipt
message on top of MeshLink rather than treating `SendResult.Sent` as a read
receipt.

## Troubleshooting

- **`send()` throws `InvalidStateTransition`** — MeshLink is not currently
  `Running`. Start the runtime first, or resume it if it is paused.
- **No peers appear after `start()`** — check permissions and Bluetooth prompts
  first, then confirm both devices use the same `appId`. For full-session
  visibility, make sure your long-lived collectors were attached before
  startup.
- **`SendResult.NotSent(UNREACHABLE)` keeps happening** — no usable route or
  transport path is available yet. Wait for peer discovery and route
  convergence, then inspect `diagnosticEvents` for the current delivery failure
  reason.
- **Trust reset leaves the UI in a confusing state** — `forgetPeer()` clears
  MeshLink trust and session continuity, but it does not clear your app-owned
  labels, conversation history, or support notes. Clean those up explicitly in
  your host app.

## Related docs

- [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md)
- [How to use MeshLink from Swift](use-meshlink-from-swift.md)
- [How to structure a robust MeshLink integration](structure-a-robust-meshlink-integration.md)
- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [MeshLink runtime behavior reference](../reference/meshlink-runtime-behavior.md)
- [Glossary and acronym reference](../reference/glossary.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
- [The trust model](../explanation/trust-model.md)
- [The peer lifecycle model](../explanation/peer-lifecycle.md)
- [Power management](../explanation/power-management.md)
