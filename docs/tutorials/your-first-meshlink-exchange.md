# Your first MeshLink exchange

In this tutorial, we will build a minimal Android host-side MeshLink
controller, discover a nearby proof peer, and send a first message
successfully.

This lesson is intentionally narrow:

- we use Android for the shortest path to a visible result
- we use a proof app on a second device as the receiving peer

If you still need to add the library to your build, follow [How to add MeshLink to your app](../how-to/add-meshlink-to-your-app.md).

If you need full Android + iOS bootstrap guidance after that, follow [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md).

## Before you start

You need:

- an Android app that can depend on MeshLink
- a second device running one of the MeshLink proof apps
- the Android host app already has the permissions described in [How to unblock MeshLink permissions on Android and iOS](../how-to/unblock-meshlink-permissions.md)

We will create one controller class and log the full flow.

## 1. Create the runtime

Create a single long-lived MeshLink runtime for your app process:

```kotlin
import android.content.Context
import ch.trancee.meshlink.api.meshLink
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig

fun createMeshLink(context: Context): MeshLinkApi {
    val config: MeshLinkConfig = meshLinkConfig {
        appId = "com.example.meshlink.tutorial"
        regulatoryRegion = RegulatoryRegion.DEFAULT
    }
    return meshLink(config = config, context = context.applicationContext)
}
```

At this point you have a runtime object, but it is not doing anything yet.

## 2. Add a tiny controller

Now create a controller that starts the runtime, watches peers, and keeps the
first discovered peer ID.

```kotlin
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MeshLinkTutorialController(
    private val meshLink: MeshLinkApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var firstPeerId: PeerId? = null

    fun start() {
        scope.launch {
            println("start() -> ${meshLink.start()}")
        }

        scope.launch {
            meshLink.peerEvents.collect { event ->
                when (event) {
                    is PeerEvent.Found -> {
                        firstPeerId = event.peerId
                        println("Peer found: ${event.peerId.value}")
                    }
                    is PeerEvent.StateChanged -> {
                        println("Peer state changed: ${event.peerId.value} -> ${event.state}")
                    }
                    is PeerEvent.Lost -> {
                        println("Peer lost: ${event.peerId.value}")
                    }
                }
            }
        }

        scope.launch {
            meshLink.messages.collect { message ->
                println(
                    "Message from ${message.originPeerId.value}: ${message.payload.decodeToString()}"
                )
            }
        }
    }

    fun stop() {
        scope.launch {
            println("stop() -> ${meshLink.stop()}")
            scope.cancel()
        }
    }

    fun sendHello() {
        val peerId = firstPeerId ?: return
        scope.launch {
            val result = meshLink.send(peerId, "hello mesh".encodeToByteArray())
            println("sendHello() -> $result")
        }
    }
}
```

## 3. Start the controller

Wire the controller into a screen, activity, or app-owned service and call `start()`.

```kotlin
class MainViewModel(context: Context) {
    private val controller = MeshLinkTutorialController(createMeshLink(context))

    fun onStartMeshLink() {
        controller.start()
    }

    fun onStopMeshLink() {
        controller.stop()
    }

    fun onSendHello() {
        controller.sendHello()
    }
}
```

When you trigger your start action, the first visible result should be a line
like:

```text
start() -> Started
```

## 4. Wait for a nearby peer

Launch a MeshLink proof app on a second device with the same `appId` and wait
for discovery.

You should soon see a line like:

```text
Peer found: <peer-id>
```

If you do not, stop here and fix discovery before moving on. If the missing
piece is still platform permission setup, use [How to unblock MeshLink permissions on Android and iOS](../how-to/unblock-meshlink-permissions.md).

## 5. Send the first message

Call `sendHello()` after the peer appears.

The expected result is:

```text
sendHello() -> Sent
```

You now have a complete first exchange from a host app through the MeshLink
runtime.

## 6. Verify the result on the receiving device

On the proof peer, confirm that the message arrived.

The visible result might be a UI entry or a retained log line such as:

```text
MSG from ... text=hello mesh
```

If you see that result, the lesson succeeded.

## 7. Stop cleanly

Call `stop()` when your app is done with MeshLink.

The expected final line is:

```text
stop() -> Stopped
```

## What you just learned

You now know how to:

- create a MeshLink runtime
- start and stop it
- observe peer discovery
- send a payload
- confirm visible success on a peer

Next:

- for production-shaped bootstrap and platform setup, use [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- for the complete public API, use the [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- for design guidance and best practices, read [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
