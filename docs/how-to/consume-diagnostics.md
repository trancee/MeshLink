# How to Consume Diagnostic Events

## When to use this

You're integrating MeshLink and want runtime visibility into protocol behavior — connection events, routing changes, errors, health metrics.

## Steps

### 1. Collect diagnostic events

```kotlin
val meshLink = MeshLink.createAndroid(context, config)

scope.launch {
    meshLink.start()
}

scope.launch {
    meshLink.diagnosticEvents.collect { event ->
        log("${event.severity} ${event.code}: ${event.payload}")
    }
}
```

### 2. Filter by severity

```kotlin
meshLink.diagnosticEvents
    .filter { it.severity == DiagnosticLevel.ERROR || it.severity == DiagnosticLevel.WARN }
    .collect { event ->
        alertOps(event)
    }
```

Severity levels:
- **ERROR** — system integrity at risk, immediate attention needed
- **WARN** — degraded but operational, monitor
- **INFO** — normal protocol activity, informational
- **DEBUG** — verbose internal state (not emitted by default)

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

Emits every `diagnostics.healthSnapshotIntervalMillis` (default: 5000ms).

### 5. React to specific codes

```kotlin
meshLink.diagnosticEvents.collect { event ->
    when (event.code) {
        DiagnosticCode.HANDSHAKE_EVENT -> {
            val payload = event.payload as DiagnosticPayload.HandshakeEvent
            log("Handshake with ${payload.peerId}: ${payload.stage}")
        }
        DiagnosticCode.ROUTE_CHANGED -> {
            val payload = event.payload as DiagnosticPayload.RouteChanged
            log("Route to ${payload.destination}: cost=${payload.cost}")
        }
        DiagnosticCode.BUFFER_PRESSURE -> {
            // Back-pressure: slow down sends
        }
        else -> {}
    }
}
```

### 6. Disable diagnostics entirely (zero overhead)

```kotlin
val config = meshLinkConfig("com.example.myapp") {
    diagnostics { enabled = false }
}
```

When disabled, the entire diagnostic pipeline is short-circuited. No allocations, no flow emissions.

## Performance notes

- Payload lambdas are evaluated lazily: `diagnosticSink.emit(code) { expensivePayload }` — the lambda only runs if a collector is attached
- The SharedFlow uses `DROP_OLDEST` — if you collect slowly, old events are silently dropped (check `DiagnosticEvent.droppedCount`)
- Zero allocation when no collector is attached and `enabled = true`

## iOS (Swift)

SKIE converts the `Flow<DiagnosticEvent>` to a Swift `AsyncSequence`:

```swift
for await event in meshLink.diagnosticEvents {
    print("\(event.severity) \(event.code)")
}
```
