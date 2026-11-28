# Choose and customize a preset

A preset sets how a node behaves: whether it relays and holds messages, how long messages live, whether read receipts flow and how large a message may be. This guide picks a built-in preset for an app and, where none fits, builds the app's own preset from one.

Before you start, add MeshLink to your app as in [Get started](../tutorials/get-started.md).

## Pick the preset closest to your app

| Your app | Preset |
|---|---|
| Text chat between people without a network | `OfflineChat` |
| Alerts that must reach as many phones as possible | `EmergencyBroadcast` |
| Showing who is nearby, without relaying | `ProximityDiscovery` |
| A game between players in one room | `LocalMultiplayer` |
| Teams spread over an area, such as rescue crews | `FieldTeams` |

[Presets](../reference/presets.md) lists every value each preset sets. Phones with different presets still form one mesh and relay each other's messages, so a preset is a choice for your app, not a contract with other apps.

Pick a security profile too: `Unsecured`, `Classical` or `PostQuantum`. [Security profile guarantees](../reference/security-profile-guarantees.md) says what each one protects. Every node of a mesh needs the same security profile and mesh ID.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#choose-preset -->
```kotlin
val config =
    meshLinkConfig(Preset.FieldTeams, SecurityProfile.PostQuantum) {
        meshId = "Rescue team"
    }.build()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#choose-preset -->
```swift
let config = try meshLinkConfig(preset: Preset.FieldTeams.shared, securityProfile: SecurityProfilePostQuantum.shared) {
    $0.meshId = "Rescue team"
}.build()
```

## Change what the preset doesn't fit

When a built-in preset is close but not right, build a `Preset.Custom` from it. The custom preset starts from its base's values, and its block overrides the ones your app needs. The block of `meshLinkConfig` can then override a value once more, for this node only.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#custom-preset -->
```kotlin
val quietChat =
    Preset.Custom("Quiet chat", Preset.OfflineChat) {
        readReceipts.send = false
        messageLifetimeSeconds = 3_600
    }
val config =
    meshLinkConfig(quietChat, SecurityProfile.Classical) {
        meshId = "Quiet chat"
        power = Power.Responsive
    }.build()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#custom-preset -->
```swift
let quietChat = Preset.Custom(name: "Quiet chat", base: Preset.OfflineChat.shared) {
    $0.readReceipts.send = false
    $0.messageLifetimeSeconds = 3_600
}
let config = try meshLinkConfig(preset: quietChat, securityProfile: SecurityProfileClassical.shared) {
    $0.meshId = "Quiet chat"
    $0.power = .responsive
}.build()
```

## Check what the node will run with

`describe()` prints every value with where it came from: `preset`, `override` or `default`. For the custom preset above, `config.describe()` prints:

```text
preset = Custom("Quiet chat", base = OfflineChat) (override)
securityProfile = Classical (override)
meshId = "Quiet chat" (override)
meshKey = none (default)
l2capUpgrade = true (default)
diagnostics.debug = false (default)
diagnostics.platformLog = false (default)
density = Automatic (preset)
power = Responsive (override)
routedUnicast = true (preset)
broadcastOrigination = true (preset)
broadcastPriority = false (preset)
holding = true (preset)
messageLifetimeSeconds = 3600 (preset)
readReceipts.requestByDefault = true (preset)
readReceipts.send = false (preset)
limits.maxUnicastPayload = 131072 (preset)
limits.maxBroadcastPayload = 1024 (preset)
```

If `build()` throws, a value is outside what MeshLink allows. [Configuration](../reference/configuration.md) lists each setting's allowed values.
