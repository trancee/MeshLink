# Send messages and read receipts

This guide sends a message to a peer, learns when the recipient read it, and handles the reasons MeshLink refuses a message. It assumes a running node and a peer from `peers`, as in [Get started](../tutorials/get-started.md).

## Send a message and learn when it was read

A message reports its progress as a status: `Queued`; `Held` while there is no way to the recipient; `Sent`; `Delivered` when the recipient's app took it; and `Read` once the recipient marks it read. `Expired` and `Failed` end it without delivery.

Ask for a read receipt in the send options. Without the option, the preset decides: `OfflineChat` and `FieldTeams` ask by default, the others don't.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#send-receipt -->
```kotlin
val options = SendOptions(readReceipt = ReadReceiptRequest.Request)
val status =
    when (val result = node.send(to, "Are you safe?".encodeToByteArray(), options)) {
        is SendResult.Accepted -> {
            result.message.status.first {
                it == DeliveryStatus.Read || it == DeliveryStatus.Expired || it is DeliveryStatus.Failed
            }
        }

        is SendResult.Rejected -> {
            error("Not sent: ${result.reason}")
        }
    }
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#send-receipt -->
```swift
let options = MeshSendOptions(readReceipt: .request)
guard case .accepted(let message) = try node.send(Data("Are you safe?".utf8), to: peer, options: options) else {
    throw CancellationError()
}
var status = message.status
for await update in message.statusUpdates {
    status = update
    if case .failed = update { break }
    if update == .read || update == .expired { break }
}
```

## Mark a received message read

On the recipient, mark a message read once the user has seen it. MeshLink sends the read receipt only if the sender asked for one and the recipient's config allows it (`readReceipts.send`). Marking a message twice sends nothing more.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#mark-read -->
```kotlin
val message = node.messages.first()
val text = message.payload.decodeToString()
message.markRead()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#mark-read -->
```swift
var text: String?
for await message in node.receivedMessages {
    text = String(decoding: message.payload, as: UTF8.self)
    message.markRead()
    break
}
```

## Wait when the queue is full

A node holds up to 1 MiB of payloads it hasn't finished sending. Past that, `send` returns `Rejected(QueueFull)`. Wait for room once with `awaitSendCapacity()`, then send again; never retry in a loop.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#queue-full -->
```kotlin
var result = node.send(to, payload)
if (result is SendResult.Rejected && result.reason == RejectionReason.QueueFull) {
    node.awaitSendCapacity()
    result = node.send(to, payload)
}
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#queue-full -->
```swift
var result = try node.send(payload, to: peer)
if case .rejected(.queueFull) = result {
    try await node.awaitSendCapacity()
    result = try node.send(payload, to: peer)
}
```

## Keep messages under the size limit

`send` returns `Rejected(TooLarge)` above the config's `limits.maxUnicastPayload`: 128 KiB in `OfflineChat`, `LocalMultiplayer` and `FieldTeams`, and 4 KiB in the others. Split larger content yourself, or lower the limit to keep your app's messages small ([Configuration](../reference/configuration.md)).

MeshLink never compresses payloads. If you compress, never compress secret data together with data someone else can influence: the length of the encrypted message then leaks the secret. Compress only data that is all public or all secret.

## Handle the other refusals

- `NotRunning`: the node is stopped, starting or stopping, or it is blocked because its identity is unavailable. Start it, or wait for `Running`. A node blocked for any other reason still accepts messages and sends them once it runs.
- `UnknownPeer`: the destination is this node's own ID, or, in Swift, an ID that came from neither a peer nor a message.

If messages are accepted but never delivered, see [Troubleshoot messages that aren't delivered](troubleshoot-messages-not-delivered.md).
