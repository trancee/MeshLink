# Broadcast to the mesh

A broadcast goes to every node within a number of hops, not to one peer. Use it for alerts and announcements that everyone nearby needs. This guide broadcasts a message and receives broadcasts from others. It assumes a running node, as in [Get started](../tutorials/get-started.md).

## Broadcast a message

Choose how far the broadcast travels with `hopLimit`, from 1 (direct neighbors only) to 255, the default. A smaller limit spends less airtime in a large mesh.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#broadcast -->
```kotlin
val alert = "Water at the school".encodeToByteArray()
val status =
    when (val result = node.broadcast(alert, hopLimit = 4)) {
        is SendResult.Accepted -> result.message.status.first { it != DeliveryStatus.Queued }
        is SendResult.Rejected -> error("Not broadcast: ${result.reason}")
    }
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#broadcast -->
```swift
guard case .accepted(let message) = try node.broadcast(Data("Water at the school".utf8), hopLimit: 4) else {
    throw CancellationError()
}
var status = message.status
for await update in message.statusUpdates where update != .queued {
    status = update
    break
}
```

A broadcast is `Sent` once its first copy is on air, and `Expired` when its lifetime ends. It never becomes `Delivered` or `Read`: broadcasts get no receipts, so you can't learn who received one.

`broadcast` refuses with `TooLarge` above `limits.maxBroadcastPayload` (at most 1,024 bytes), with `InvalidHopLimit` outside 1 to 255, and with `BroadcastDisabled` when the config turns `broadcastOrigination` off. `QueueFull` works as it does for messages; see [Send messages and read receipts](send-messages.md#wait-when-the-queue-is-full).

In `Classical` and `PostQuantum`, every node checks the sender's signature before it delivers or forwards a broadcast, but every member of the mesh can read it. Never broadcast what only one peer may read.

## Receive broadcasts

Broadcasts arrive on the same stream as messages, marked as broadcasts:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#receive-broadcast -->
```kotlin
val alert = node.messages.first { it.broadcast }
val text = alert.payload.decodeToString()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#receive-broadcast -->
```swift
var text: String?
for await message in node.receivedMessages where message.isBroadcast {
    text = String(decoding: message.payload, as: UTF8.self)
    break
}
```

To get broadcasts through first when the mesh is busy, use the `EmergencyBroadcast` preset, or turn on `broadcastPriority` in a custom preset ([Choose and customize a preset](choose-and-customize-a-preset.md)).

If broadcasts don't reach the phones you expect, see [Troubleshoot messages that aren't delivered](troubleshoot-messages-not-delivered.md) and [Troubleshoot peers that aren't found](troubleshoot-peers-not-found.md).
