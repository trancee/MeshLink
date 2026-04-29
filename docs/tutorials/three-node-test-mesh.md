# Tutorial: Building a 3-Node Test Mesh

> **Time:** ~20 minutes  
> **What you'll build:** A JVM unit test that simulates a 3-node BLE mesh, routes a message across two hops, and verifies delivery — with no hardware.  
> **Prerequisites:** The MeshLink project checked out, `./gradlew :meshlink:jvmTest` passing.

---

## 1. Understand the test topology

We'll build this network:

```
A ←→ B ←→ C
     (A cannot see C directly)
```

Node A sends a 1KB message to Node C. It must route through B. We'll verify:
- B relays the message
- C receives and decrypts it
- A gets a Delivery ACK back

## 2. Create the test file

Create `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/tutorial/ThreeNodeTutorialTest.kt`:

```kotlin
package ch.trancee.meshlink.tutorial

import ch.trancee.meshlink.integration.MeshTestHarness
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ThreeNodeTutorialTest {

    @Test
    fun threeNodeRelayDelivery() = runTest {
        // Step 3: Build the harness
        val harness = MeshTestHarness.builder()
            .nodeCount(3)
            .linearTopology()  // A↔B, B↔C, no A↔C link
            .build(this)

        // Step 4: Start all nodes and complete handshakes
        harness.startAll()
        harness.completeAllHandshakes()

        // Step 5: Send a message from A to C
        val payload = ByteArray(1024) { it.toByte() }
        val nodeA = harness.node(0)
        val nodeC = harness.node(2)

        val result = nodeA.engine.send(
            recipient = nodeC.keyHash,
            data = payload
        )

        // Step 6: Advance virtual time to allow routing + transfer
        harness.advanceTimeBy(10.seconds)

        // Step 7: Verify delivery
        val received = nodeC.receivedMessages.first()
        assertTrue(received.data.contentEquals(payload))
        assertEquals(nodeA.keyHash, received.senderKeyHash)
    }
}
```

## 3. Understand MeshTestHarness

`MeshTestHarness` is the N-node integration test framework built in M005/S01. Key features:

- **Real Noise XX handshakes** — each node gets a live `Identity`, runs the full 3-message handshake, pins keys in `TrustStore`
- **Virtual time** — `advanceTimeBy()` moves the clock without wall-clock waiting
- **VirtualMeshTransport** — in-process BLE simulator connecting nodes by topology
- **Full MeshEngine composition** — routing, transfer, messaging, power all wired

## 4. The `linearTopology()` helper

This creates BLE links:
- Node 0 ↔ Node 1
- Node 1 ↔ Node 2
- No direct link between Node 0 and Node 2

The Babel routing engine discovers the route A→B→C through Hello/Update exchanges that happen during `completeAllHandshakes()`.

## 5. Run the test

```bash
./gradlew :meshlink:jvmTest --tests "*.ThreeNodeTutorialTest"
```

You should see it pass. The message is:
1. Sealed with Noise K (E2E encrypted to C's public key)
2. Chunked by TransferEngine
3. Routed by Babel (A's RoutingEngine knows C is reachable via B)
4. Relayed by B's DeliveryPipeline (cut-through forwarding)
5. Reassembled and decrypted at C
6. Delivery ACK flows back C→B→A

## 6. Inspect diagnostics

Add diagnostic observation to your test:

```kotlin
val diagnostics = mutableListOf<DiagnosticEvent>()
nodeA.diagnosticSink.events.collect { diagnostics.add(it) }

// After sending...
harness.advanceTimeBy(10.seconds)

// Check what happened
diagnostics.filter { it.severity == Severity.LOG }.forEach {
    println("${it.code}: ${it.payload}")
}
```

You'll see events like `ROUTE_CHANGED`, `TRANSFER_STARTED`, `DELIVERY_ACK_RECEIVED`.

## 7. Test failure scenarios

Try simulating a link failure:

```kotlin
// Break the A↔B link after message is in-flight
harness.breakLink(0, 1)
harness.advanceTimeBy(30.seconds)

// Verify peer lifecycle transitions
val nodeAState = nodeA.presenceTracker.peerState(nodeB.keyHash)
assertEquals(PeerState.DISCONNECTED, nodeAState)

// After 60s grace period:
harness.advanceTimeBy(60.seconds)
val finalState = nodeA.presenceTracker.peerState(nodeB.keyHash)
// Gone — evicted after two sweeps
```

## What you've accomplished

You now know how to:
- Build N-node test topologies without BLE hardware
- Run full protocol scenarios (handshake → routing → transfer → delivery)
- Use virtual time to control timing deterministically
- Inspect diagnostic events from protocol subsystems
- Simulate link failures and verify lifecycle transitions

## Next steps

- [How to Write a Protocol Integration Test](../how-to/write-integration-test.md) — more complex scenarios
- [Explanation: Understanding Babel Routing](../explanation/understanding-babel-routing.md) — how routes converge
- [Explanation: Cut-Through vs Store-and-Forward](../explanation/cut-through-relay.md) — what B does with the chunks
