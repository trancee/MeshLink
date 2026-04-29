# How to Consume Diagnostic Events

## When to use this

You're integrating MeshLink and want runtime visibility into protocol behavior — connection events, routing changes, errors, health metrics.

## Steps

### 1. Collect diagnostic events

```kotlin
val meshLink = MeshLink.createAndroid(context, config)
meshLink.start()

// Collect all events
scope.launch {
    meshLink.diagnosticEvents.collect { event ->
        log("${event.severity} ${event.code}: ${event.payload}")
    }
}
```

### 2. Filter by severity

```kotlin
meshLink.diagnosticEvents
    .filter { it.severity == Severity.CRITICAL || it.severity == Severity.THRESHOLD }
    .collect { event ->
        alertOps(event)
    }
```

Severity tiers:
- **Critical (4 codes):** Decryption failed, handshake failed, identity compromised, storage corruption
- **Threshold (8 codes):** Buffer full, transfer timeout, route expired, connection limit reached
- **Log (15 codes):** Peer discovered, route changed, handshake event, transfer progress

### 3. Read point-in-time health

```kotlin
val health: MeshHealthSnapshot = meshLink.meshHealth()
println("Connected peers: ${health.connectedPeerCount}")
println("Buffer utilization: ${health.bufferUtilizationPercent}%")
println("Active transfers: ${health.activeTransferCount}")
```

### 4. Subscribe to periodic health flow

```kotlin
meshLink.meshHealthFlow.collect { snapshot ->
    updateDashboard(snapshot)
}
```

### 5. React to specific codes

```kotlin
meshLink.diagnosticEvents.collect { event ->
    when (event.code) {
        DiagnosticCode.HANDSHAKE_FAILED -> {
            val payload = event.payload as DiagnosticPayload.HandshakeFailed
            log("Handshake failed with ${payload.peerId}: ${payload.reason}")
        }
        DiagnosticCode.ROUTE_CHANGED -> {
            val payload = event.payload as DiagnosticPayload.RouteChanged
            log("Route to ${payload.destination} via ${payload.nextHop}, metric=${payload.metric}")
        }
        DiagnosticCode.BUFFER_FULL -> {
            // Back-pressure: slow down sends
        }
        else -> {}
    }
}
```

### 6. Opt out entirely (zero overhead)

If you don't need diagnostics, pass nothing — `NoOpDiagnosticSink` is the default internally. The `diagnosticEvents` flow simply never emits. Payload lambdas are never evaluated.

## Performance notes

- Diagnostic payloads use lazy evaluation: `diagnosticSink.emit(code) { expensivePayload }` — the lambda only runs if someone is collecting
- The SharedFlow uses `DROP_OLDEST` with a ring buffer — if you collect slowly, old events are dropped (check `droppedCount` on MeshHealthSnapshot)
- Zero allocation when no collector is attached

## iOS (Swift)

SKIE converts the `Flow<DiagnosticEvent>` to a Swift `AsyncSequence`:

```swift
for await event in meshLink.diagnosticEvents {
    print("\(event.severity) \(event.code)")
}
```
