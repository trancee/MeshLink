# How to Write a Protocol Integration Test

## When to use this

You need to test cross-subsystem behavior (routing + transfer + delivery + handshake) in a controlled virtual environment.

## Steps

### 1. Use MeshTestHarness

```kotlin
import ch.trancee.meshlink.integration.MeshTestHarness

@Test
fun myScenario() = runTest {
    val harness = MeshTestHarness.builder()
        .nodeCount(3)
        .linearTopology()
        .build(this)

    harness.startAll()
    harness.completeAllHandshakes()

    // Your test logic here

    harness.advanceTimeBy(10.seconds)

    // Assertions
}
```

### 2. Available topologies

```kotlin
.linearTopology()      // A↔B↔C (no A↔C)
.fullMeshTopology()    // All nodes connected to all
.starTopology(center = 1)  // All nodes connected to node 1
.customTopology { links ->
    links.add(0 to 1)
    links.add(1 to 2)
    links.add(0 to 2)  // triangular
}
```

### 3. Access node internals

```kotlin
val nodeA = harness.node(0)
nodeA.engine          // MeshEngine instance
nodeA.keyHash         // This node's key hash (ByteArray)
nodeA.identity        // Full Identity (Ed25519 + X25519)
nodeA.diagnosticSink  // DiagnosticSink for event inspection
nodeA.receivedMessages // List of messages delivered to this node
```

### 4. Simulate network events

```kotlin
// Break a link
harness.breakLink(0, 1)

// Restore a link
harness.restoreLink(0, 1)

// Simulate peer disconnect (triggers lifecycle FSM)
harness.simulatePeerLost(nodeIndex = 0, peerIndex = 1)
```

### 5. Advance virtual time

```kotlin
// All timer-driven behavior (routing intervals, sweep, power state) advances
harness.advanceTimeBy(30.seconds)

// Process pending coroutines without advancing time
harness.runCurrent()
```

### 6. Assert on diagnostics

```kotlin
val events = nodeA.diagnosticSink.collectedEvents()
assertTrue(events.any { it.code == DiagnosticCode.ROUTE_CHANGED })
```

### 7. Assert on sent frames (wire-level)

```kotlin
val frames = nodeB.transport.sentFrames
    .filter { it.destination.contentEquals(nodeC.keyHash) }
assertTrue(frames.isNotEmpty(), "B should relay to C")
```

## Key patterns

### Testing cut-through relay

```kotlin
// Verify B starts forwarding chunks before receiving all from A
val chunksSentByB = nodeB.transport.sentFrames
    .filter { /* chunk type to C */ }
val chunksReceivedByB = nodeB.transport.receivedFrames
    .filter { /* chunk type from A */ }

// B sent some chunks before receiving all
assertTrue(chunksSentByB.size > 0)
assertTrue(chunksReceivedByB.size > chunksSentByB.size)
```

### Testing peer lifecycle

```kotlin
harness.simulatePeerLost(0, 1)
harness.advanceTimeBy(30.seconds)  // first sweep
assertEquals(PeerState.DISCONNECTED, nodeA.presenceTracker.peerState(nodeB.keyHash))

harness.advanceTimeBy(30.seconds)  // second sweep
// Now Gone — evicted
```

### Testing pseudonym rotation

```kotlin
val initialPseudonym = nodeA.engine.advertisementPseudonym
harness.advanceTimeBy(15.minutes)  // one epoch
val rotatedPseudonym = nodeA.engine.advertisementPseudonym
assertFalse(initialPseudonym.contentEquals(rotatedPseudonym))
```

## Gotchas

- Pass `backgroundScope` (not `this`) when creating the harness — timer loops need a scope that doesn't trigger `UncompletedCoroutinesError`
- Use `batteryPollIntervalMillis = 300_000L` in test configs to prevent OOM from timer explosion
- Add `-Xmx1g` JVM args for tests with 3+ nodes under Kover instrumentation
- `testScheduler.runCurrent()` after `tryEmit` to flush subscriber continuations
