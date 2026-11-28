# Get started: send a message between two phones

In this tutorial we build an app that runs on two phones. Each phone finds the other one over Bluetooth, sends it the message "Hello from Alice" over an encrypted link, receives the other phone's message, and sees its own message reach the status Delivered.

Each step shows the Kotlin code first, then the Swift code. Follow the language of your app. The code is copied from tests that run on every build, so it compiles and works as shown.

> **Status:** MeshLink 0.1.0 is not released yet. The coordinates in step 1 resolve from the first release on.

## What you need

- Two phones with Bluetooth turned on. Each one is either an Android phone with Android 12 or later, or an iPhone with iOS 15 or later. Any mix works.
- An Android app in Android Studio, or an iOS app in Xcode, that you can run on both phones.

## Step 1: Add MeshLink to your app

In Kotlin, add the dependency to your app module's `build.gradle.kts`. In a Kotlin Multiplatform module, put it in `commonMain.dependencies` instead.

```kotlin
dependencies {
    implementation("ch.trancee.meshlink:meshlink:0.1.0")
}
```

In Swift, open **File > Add Package Dependencies** in Xcode, enter `https://github.com/trancee/meshlink-swift`, choose version 0.1.0 and add the `MeshLink` library to your app target. In a `Package.swift`, the dependency is:

```swift
.package(url: "https://github.com/trancee/meshlink-swift", from: "0.1.0"),
```

On iOS, also add the key `NSBluetoothAlwaysUsageDescription` to your app's `Info.plist`, with a sentence that tells the user why your app uses Bluetooth. iOS shows that sentence when it asks for permission.

Build the app. It builds without errors.

## Step 2: Describe the mesh

A configuration says which mesh the node joins and how it behaves. Both phones must use the same mesh ID and security profile, or they won't see each other. The `OfflineChat` preset fits a chat between phones, and the `Classical` security profile encrypts every link.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#configure -->
```kotlin
val config =
    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
        meshId = "Get started"
    }.build()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#configure -->
```swift
let config = try meshLinkConfig(preset: Preset.OfflineChat.shared, securityProfile: SecurityProfileClassical.shared) {
    $0.meshId = "Get started"
}.build()
```

## Step 3: Create the node

A node is your app's member of the mesh. It runs on a platform, which owns the phone's Bluetooth radio. In Kotlin on Android, get the platform from any `Context` of your app; MeshLink uses its application. In Kotlin code that runs on iOS, use `MeshPlatform.ios()` instead.

<!-- region: meshlink/src/androidHostTest/kotlin/ch/trancee/meshlink/docs/GetStartedAndroidTest.kt#platform-android -->
```kotlin
val platform = MeshPlatform.android(context)
```

Then create the node on that platform:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#create -->
```kotlin
val node = MeshLink.create(config, platform)
```

In Swift, one call creates the node on the iPhone's radio:

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#create-ios -->
```swift
let node: any MeshNode = MeshLink.create(config: config)
```

The node exists, but it does not use the radio yet: its state is `Stopped`.

## Step 4: Start the node

`start()` suspends until the node runs or is blocked, so call it from a coroutine, such as `lifecycleScope.launch { … }` in an Android activity, or from a Swift `Task { … }`.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#start -->
```kotlin
node.start()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#start -->
```swift
try await node.start()
```

Run the app on one phone.

- On an iPhone, iOS asks whether the app may use Bluetooth. Tap **Allow**.
- On Android, MeshLink never asks for permissions itself. Open **Settings > Apps >** your app **> Permissions > Nearby devices**, choose **Allow**, and start the app again.

The node's state (`state` in Kotlin, `currentState` in Swift) is now `Running`. If it is `Blocked`, its requirements say what is missing, such as a denied permission or Bluetooth turned off; fix it and start again.

## Step 5: Receive messages

Messages for this node arrive on `messages` in Kotlin and `receivedMessages` in Swift. Collect them in their own coroutine or `Task`, next to the code of the next steps, so that the node takes each message as it arrives.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#receive -->
```kotlin
val message = node.messages.first()
val text = message.payload.decodeToString()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#receive -->
```swift
var text: String?
for await message in node.receivedMessages {
    text = String(decoding: message.payload, as: UTF8.self)
    break
}
```

Show `text` in your app's interface, or log it. Nothing arrives yet: the other phone hasn't sent anything.

## Step 6: Find the other phone

Run the app on the second phone too. Each node lists the nodes it can reach as peers. Wait for the first one:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#peers -->
```kotlin
val peer = node.peers.first { it.isNotEmpty() }.first()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#peers -->
```swift
var peer: MeshPeer?
for await peers in node.peerUpdates where !peers.isEmpty {
    peer = peers.first
    break
}
```

Once the phones find each other, `peer` is the other phone. Its `secured` is true, because the `Classical` profile encrypts the link between them.

## Step 7: Send a message

Send the other phone a message, then wait until its app has taken it:

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#send -->
```kotlin
val result = node.send(peer.nodeId, "Hello from Alice".encodeToByteArray())
val status =
    when (result) {
        is SendResult.Accepted -> result.message.status.first { it == DeliveryStatus.Delivered }
        is SendResult.Rejected -> error("Not sent: ${result.reason}")
    }
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#send -->
```swift
guard case .accepted(let message) = try node.send(Data("Hello from Alice".utf8), to: peer.id) else {
    return nil
}
for await status in message.statusUpdates where status == .delivered {
    break
}
```

Both phones run the same code, so each one sends "Hello from Alice" and receives the other's message. On each phone, the text from step 5 shows "Hello from Alice", and the sent message's status reaches `Delivered`.

## Step 8: Close the node

When your app no longer needs the mesh, for example when its last screen closes, close the node. It stops and frees the radio, and its state ends at `Stopped`.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/docs/GetStartedTest.kt#close -->
```kotlin
node.close()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/GetStartedTests.swift#close -->
```swift
node.close()
```

## What we built

We built an app whose node joins a mesh, finds another phone, exchanges an encrypted message with it and sees the message delivered.

If a step didn't give the result it describes, these pages walk through the causes:

- [Troubleshoot peers that aren't found](../how-to/troubleshoot-peers-not-found.md)
- [Troubleshoot messages that aren't delivered](../how-to/troubleshoot-messages-not-delivered.md), or arrive late
- [Troubleshoot high battery use](../how-to/troubleshoot-high-battery-use.md)

To go further, see what each [preset](../reference/presets.md) sets and every [configuration](../reference/configuration.md) setting, or browse the [documentation](../README.md).
