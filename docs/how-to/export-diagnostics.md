# Export diagnostics for a bug report

When MeshLink misbehaves, a diagnostics export lets someone else see what the node saw. This guide exports the report and attaches it to a bug report.

## Export the report

Export from the node that has the problem, while it still runs or right after. The export holds the node's state now and the last 500 events it emitted since it was created.

<!-- region: meshlink-testing/src/commonTest/kotlin/ch/trancee/meshlink/samples/Samples.kt#export -->
```kotlin
val report = node.diagnostics.export()
```

<!-- region: tools/swiftpm-consumer/Tests/MeshLinkKitConsumerTests/HowToTests.swift#export -->
```swift
let report = try await node.exportDiagnostics()
```

In Swift, `exportDiagnostics()` runs the export for a caller on the main actor.

For a problem between phones, such as a message that never arrives, export from each of them.

## Check what it contains

The report is JSON. It holds no key, payload, NodeId, mesh ID or Bluetooth address: a peer appears as a `PeerRef`, which changes on every `start()`. [Diagnostics export](../reference/diagnostics-export.md) lists every field.

It does hold the configuration, the codes and times of the events, and the platform and OS versions. Let the user see the report before you send it.

## Attach it

Open an issue with the reports attached, and describe what you expected and what happened. For each code in a report, [Diagnostics](../reference/diagnostics.md) lists the likely cause and fix. The troubleshooting pages walk through the common symptoms:

- [Troubleshoot peers that aren't found](troubleshoot-peers-not-found.md)
- [Troubleshoot messages that aren't delivered](troubleshoot-messages-not-delivered.md)
- [Troubleshoot high battery use](troubleshoot-high-battery-use.md)
