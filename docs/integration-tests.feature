# MeshLink Integration Tests
#
# Gherkin specification of all integration test scenarios exercised by
# MeshIntegrationTest.kt using the VirtualMeshTransport (in-memory BLE mock).
#
# These scenarios are living documentation — each maps 1:1 to a @Test method.
#
# ┌─────────────────────────────────────────────────────────────────────┐
# │ How to run                                                         │
# │                                                                    │
# │  All integration tests:                                            │
# │    ./gradlew :meshlink:jvmTest \                                   │
# │        --tests "io.meshlink.MeshIntegrationTest" --parallel        │
# │                                                                    │
# │  Single scenario:                                                  │
# │    ./gradlew :meshlink:jvmTest \                                   │
# │        --tests "io.meshlink.MeshIntegrationTest.selfSendLoopback"  │
# │                                                                    │
# │  Test report (after run):                                          │
# │    open meshlink/build/reports/tests/jvmTest/index.html            │
# └─────────────────────────────────────────────────────────────────────┘

@integration @virtual-mesh
Feature: BLE Mesh Peer-to-Peer Messaging
  As a MeshLink library consumer
  I want peers to discover each other and exchange messages over a BLE mesh
  So that devices can communicate without servers or internet

  # ─── Direct Messaging ──────────────────────────────────────────

  @messaging @plaintext
  Scenario: Plaintext direct message between two neighbors
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob are within BLE range of each other
    When Alice discovers Bob and Bob discovers Alice
    And Alice sends "hello mesh" to Bob
    Then Bob should receive the message "hello mesh" from Alice

  @messaging @encrypted
  Scenario: Encrypted direct message after key exchange
    Given two peers "Alice" and "Bob" are on the mesh with crypto enabled
    And Alice and Bob are within BLE range of each other
    When they exchange public keys via BLE advertisements
    And Alice sends "encrypted hello" to Bob
    Then the send should succeed
    And Bob should receive the message "encrypted hello"

  @messaging
  Scenario: Bidirectional messaging between two peers
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob have discovered each other
    When Alice sends "from alice" to Bob
    And Bob sends "from bob" to Alice
    Then Alice should receive the message "from bob"
    And Bob should receive the message "from alice"

  @messaging
  Scenario: Multiple sequential messages arrive in order
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob have discovered each other
    When Alice sends 5 sequential messages "msg-0" through "msg-4" to Bob
    Then Bob should receive all 5 messages in order

  @messaging
  Scenario: Self-send delivers via loopback
    Given a single peer "Alice" is on the mesh without encryption
    When Alice sends "self hello" to herself
    Then Alice should receive the message "self hello" from herself

  # ─── Message Chunking ──────────────────────────────────────────

  @messaging @chunking
  Scenario: Large message is chunked and reassembled at receiver
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And the MTU is configured to 185 bytes
    And Alice and Bob have discovered each other
    When Alice sends a 2000-byte payload to Bob
    Then the send should succeed
    And Bob should receive the complete 2000-byte payload intact

  # ─── Delivery Confirmation ─────────────────────────────────────

  @messaging @delivery
  Scenario: Sender receives delivery confirmation after recipient consumes message
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob have discovered each other
    When Alice sends "ack me" to Bob
    And Bob consumes the message
    Then Alice should receive a delivery confirmation matching the sent message ID

  # ─── Broadcasting ──────────────────────────────────────────────

  @messaging @broadcast
  Scenario: Broadcast reaches all neighbors
    Given three peers "Alice", "Bob", and "Charlie" are on the mesh without encryption
    And Alice is within BLE range of both Bob and Charlie
    When Alice broadcasts "hello everyone" with a max hop count of 3
    Then Bob should receive the broadcast "hello everyone"
    And Charlie should receive the broadcast "hello everyone"

  # ─── Multi-Hop Routing ─────────────────────────────────────────

  @routing @multi-hop
  Scenario: Message is routed across three hops via intermediate peer
    Given three peers "A", "B", and "C" in a linear chain topology
    And A is linked to B and B is linked to C but A is not linked to C
    And AODV route discovery is enabled
    When neighbor routes are established and route discovery propagates
    And A sends "routed hello" to C
    Then the send should succeed
    And B should relay the routed message toward C

  # ─── Peer Discovery Lifecycle ──────────────────────────────────

  @discovery
  Scenario: Peer found and lost events are emitted
    Given a peer "Alice" is on the mesh without encryption
    When Alice discovers "Bob" via BLE
    Then Alice should emit a Found event for Bob
    When Bob goes out of BLE range
    Then Alice should emit a Lost event for Bob

  @discovery @scale
  Scenario: Five-node full mesh discovery
    Given 5 peers are on the mesh without encryption
    And every peer is within BLE range of every other peer
    When all peers discover each other
    Then each peer should report 4 connected peers

  # ─── Handshake ─────────────────────────────────────────────────

  @handshake @crypto
  Scenario: Noise XX handshake registers peer keys without advertisement
    Given two peers "Alice" and "Bob" are on the mesh with crypto enabled
    And Alice and Bob are within BLE range of each other
    When they discover each other without public keys in the advertisement
    And the Noise XX handshake completes
    Then Alice should know Bob's public key
    And Bob should know Alice's public key

  @handshake @crypto @collision
  Scenario: Simultaneous handshake initiation resolves gracefully
    Given two peers "Alice" and "Bob" are on the mesh with crypto enabled
    And Alice and Bob are within BLE range of each other
    When both peers discover each other simultaneously without advertisement keys
    And both peers attempt to initiate a Noise XX handshake at the same time
    Then the handshake collision should be resolved
    And Alice should know Bob's public key
    And Bob should know Alice's public key

  # ─── Diagnostics ───────────────────────────────────────────────

  @diagnostics @handshake
  Scenario: Handshake diagnostic events are emitted during key exchange
    Given two peers "Alice" and "Bob" are on the mesh with crypto enabled
    And Alice is collecting diagnostic events
    When they exchange public keys and the handshake executes
    Then Alice should emit HANDSHAKE_EVENT diagnostics
    And the diagnostics should include a handshake initiation event
    And the diagnostics should include a handshake completion event

  @diagnostics @error
  Scenario: Transport failure emits SEND_FAILED diagnostic
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob have discovered each other
    And Alice is collecting diagnostic events
    When the transport layer is configured to fail
    And Alice attempts to send "will fail" to Bob
    Then Alice should emit a SEND_FAILED diagnostic event

  # ─── Mesh Lifecycle ────────────────────────────────────────────

  @lifecycle
  Scenario: Stopping the mesh clears all health counters
    Given a peer "Alice" is on the mesh without encryption
    And Alice has discovered "Bob"
    And Alice reports 1 connected peer
    When Alice stops the mesh
    Then Alice should report 0 connected peers
    And Alice should report 0 reachable peers

  @lifecycle
  Scenario: Mesh can be stopped and restarted with full functionality
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    When Alice starts, discovers Bob, then stops
    Then Alice should report 0 connected peers
    When Alice starts again and rediscovers Bob
    Then Alice should report 1 connected peer
    And Alice should be able to send "after restart" to Bob successfully

  @lifecycle @pause
  Scenario: Pausing queues messages and resuming delivers them
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice and Bob have discovered each other
    When Alice pauses the mesh
    And Alice sends "queued msg" to Bob
    Then no data should be transmitted on the transport
    When Alice resumes the mesh
    Then Bob should receive the queued message

  # ─── Send Rejection ────────────────────────────────────────────

  @send-policy @error
  Scenario: Sending a message that exceeds the buffer capacity is rejected
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And the buffer capacity is configured to 500 bytes
    And Alice and Bob have discovered each other
    When Alice sends a 600-byte payload to Bob
    Then the send should fail

  @send-policy @error
  Scenario: Sending to an unreachable peer fails
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And Alice has started the mesh but has not discovered any peers
    When Alice sends "hello" to an unknown peer
    Then the send should fail

  @send-policy @error
  Scenario: Sending before starting the mesh fails
    Given a peer "Alice" is configured but has not started the mesh
    When Alice sends "hello" to any peer
    Then the send should fail with an IllegalStateException

  # ─── Transfer Failures ─────────────────────────────────────────

  @transfer @failure
  Scenario: Stopping the mesh emits delivery timeout for in-flight transfers
    Given a peer "Alice" is on the mesh without encryption
    And Alice has discovered "Bob" but Bob is not running a MeshLink instance
    And Alice is collecting transfer failure events
    When Alice sends a message to Bob
    And Alice stops the mesh before receiving a delivery acknowledgment
    Then Alice should receive a transfer failure with reason FAILED_DELIVERY_TIMEOUT

  # ─── Transfer Progress ─────────────────────────────────────────

  @transfer @progress
  Scenario: Chunked transfer emits progress updates
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    And the MTU is configured to 185 bytes
    And Alice and Bob have discovered each other
    And Alice is collecting transfer progress events
    When Alice sends a 2000-byte payload to Bob
    Then Alice should receive progress updates showing increasing chunk acknowledgment
    And the final progress update should show all chunks acknowledged

  # ─── Identity Rotation ─────────────────────────────────────────

  @crypto @identity
  Scenario: Rotating identity generates a new key pair
    Given a peer "Alice" is on the mesh with crypto enabled
    When Alice rotates her identity
    Then Alice's public key should change to a new value

  @crypto @identity
  Scenario: Rotating identity without crypto enabled fails
    Given a peer "Alice" is on the mesh without crypto
    When Alice attempts to rotate her identity
    Then the rotation should fail

  # ─── Power Management ──────────────────────────────────────────

  @power
  Scenario: Low battery triggers power saver mode after hysteresis
    Given a peer "Alice" is on the mesh without encryption with an injected clock
    When Alice reports a battery level of 10 percent and not charging
    And the hysteresis period of 30 seconds elapses
    And Alice reports the same battery level again
    Then Alice's power mode should be POWER_SAVER

  @power
  Scenario: Charging overrides battery level with immediate performance mode
    Given a peer "Alice" is on the mesh without encryption with an injected clock
    And Alice's power mode has been downgraded to POWER_SAVER
    When Alice reports a battery level of 10 percent and charging
    Then Alice's power mode should be PERFORMANCE

  @power
  Scenario: Medium battery activates balanced mode after hysteresis
    Given a peer "Alice" is on the mesh without encryption with an injected clock
    When Alice reports a battery level of 50 percent and not charging
    And the hysteresis period of 30 seconds elapses
    And Alice reports the same battery level again
    Then Alice's power mode should be BALANCED

  # ─── Diagnostics (Extended) ────────────────────────────────────

  @diagnostics
  Scenario: Malformed wire data emits MALFORMED_DATA diagnostic
    Given a peer "Alice" is on the mesh without encryption
    And Alice has discovered "Bob"
    And Alice is collecting diagnostic events
    When Alice receives garbled data from Bob
    Then Alice should emit a MALFORMED_DATA diagnostic event

  @diagnostics
  Scenario: Hop limit exceeded emits HOP_LIMIT_EXCEEDED diagnostic
    Given a peer "Alice" is on the mesh without encryption
    And Alice has discovered "Bob"
    And Alice is collecting diagnostic events
    When Alice receives a routed message that has exceeded its hop limit
    Then Alice should emit a HOP_LIMIT_EXCEEDED diagnostic event

  # ─── Peer Eviction ─────────────────────────────────────────────

  @discovery @eviction
  Scenario: Stale peer is evicted after consecutive sweep misses
    Given a peer "Alice" is on the mesh without encryption
    And Alice has discovered "Bob"
    When Bob disappears and Alice sweeps presence twice without seeing Bob
    Then Alice should report 0 connected peers
    And Alice should emit a PEER_PRESENCE_EVICTED diagnostic event

  # ─── Memory Pressure ───────────────────────────────────────────

  @lifecycle @memory
  Scenario: Shedding memory pressure clears stale state
    Given a peer "Alice" is on the mesh without encryption
    And Alice has accumulated stale transfer state
    When Alice sheds memory pressure
    Then the shed operation should return the list of cleared entries

  # ─── Broadcast Relay ─────────────────────────────────────────

  @broadcast @routing
  Scenario: Broadcast relays across hops with TTL decrement
    Given three peers "A", "B", "C" in a linear chain (A↔B↔C, A cannot reach C directly)
    And all peers are on the mesh without encryption
    When A broadcasts a message with maxHops=3
    Then B receives the broadcast directly
    And B relays the broadcast to C
    And C receives the broadcast via relay

  # ─── Routing ────────────────────────────────────────────────

  @routing @aodv
  Scenario: AODV route discovery queues message and sends RREQ
    Given a peer "Alice" is on the mesh with neighbor "Bob"
    When Alice sends a message to an unknown peer "C"
    Then the send should succeed (message queued for route discovery)
    And Alice should flood an RREQ through her neighbors

  @routing @diagnostics
  Scenario: Hop limit exceeded emits diagnostic
    Given a peer "Alice" is on the mesh without encryption
    When Alice receives a routed message with hopLimit=0 (already exhausted)
    Then Alice should not forward the message
    And Alice should emit a HOP_LIMIT_EXCEEDED diagnostic event

  # ─── Concurrent Transfers ───────────────────────────────────

  @transfer @concurrency
  Scenario: Concurrent transfers deliver all messages
    Given two peers "Alice" and "Bob" are on the mesh without encryption
    When Alice sends 5 messages to Bob concurrently
    Then all 5 sends should succeed
    And Bob should receive all 5 messages
    And all received payloads should match what Alice sent

  # ═══════════════════════════════════════════════════════════════
  # Festival Mesh Simulation Scenarios
  # ═══════════════════════════════════════════════════════════════
  #
  # Real-world BLE mesh scenarios at a music festival, exercising
  # multi-hop routing, dynamic topology, packet loss, connection
  # limits, latency, and asymmetric links via VirtualMeshTransport
  # with LinkProperties.
  #
  # Test class: FestivalMeshSimulationTest.kt

  # ─── Multi-Hop Relay ────────────────────────────────────────

  @festival @routing
  Scenario: Multi-hop relay across festival ground
    Given a linear chain Alice ↔ Bob ↔ Charlie ↔ Eve ↔ Frank
    And each intermediate peer has pre-installed routes to the destination
    And encryption is disabled
    When Alice sends "meet at main stage" to Frank
    Then Frank should receive the message via 4-hop relay
    And the payload should match "meet at main stage"

  # ─── Emergency Broadcast ────────────────────────────────────

  @festival @broadcast
  Scenario: Emergency broadcast reaches entire cluster
    Given 5 peers in a full-mesh topology (all linked to each other)
    And encryption is disabled
    When Alice broadcasts "⚠ Emergency: exit via Gate B" with maxHops=3
    Then all 4 other peers should receive the broadcast

  # ─── Attendee Mobility ──────────────────────────────────────

  @festival @mobility
  Scenario: Attendee walks between stages
    Given Alice ↔ Bob ↔ Charlie form a chain (Alice near Stage A, Charlie near Stage B)
    And encryption is disabled
    When Bob "walks" from Stage A to Stage B
      | step | action                      |
      | 1    | Bob unlinks from Alice       |
      | 2    | Alice sends to Bob (fails)   |
      | 3    | Bob links to Charlie         |
      | 4    | Charlie sends to Bob (works) |
    Then the send from Alice during partition should fail
    And the send from Charlie after Bob arrives should succeed

  # ─── Network Partition & Recovery ───────────────────────────

  @festival @partition
  Scenario: Network partition when bridge leaves
    Given Stage A (Alice, Bob) ↔ Eve (bridge) ↔ Stage B (Frank, Grace)
    And all intermediate peers have routes to Frank
    And encryption is disabled
    When Alice sends "before partition" to Frank
    Then Frank should receive the message
    When Eve stops (bridge breaks)
    And Ivan arrives linking Bob ↔ Ivan ↔ Frank with updated routes
    And Alice sends "after recovery" to Frank
    Then Frank should receive the recovery message via Ivan

  # ─── Packet Loss ────────────────────────────────────────────

  @festival @loss
  Scenario: Packet loss in crowded main stage area
    Given Alice and Bob are linked with 50% packet loss (seeded Random(42))
    And encryption is disabled
    When Alice sends 10 messages to Bob
    Then some messages should be received (deterministic with seed)
    And not all 10 messages should arrive (packet loss in effect)

  # ─── Connection Saturation ──────────────────────────────────

  @festival @connections
  Scenario: Connection saturation in dense crowd
    Given Alice with maxConnections=3 is linked to 5 peers (Bob–Frank)
    And encryption is disabled
    When Alice sends to all 5 peers
    Then the first 3 sends should succeed (connections established)
    And the remaining 2 sends should fail (connection limit exceeded)

  # ─── Latency Accumulation ───────────────────────────────────

  @festival @latency
  Scenario: Latency accumulates over multi-hop
    Given a 3-hop chain Alice ↔ Bob ↔ Charlie ↔ Eve with 50ms latency per link
    And all intermediate peers have routes to Eve
    And encryption is disabled
    When Alice sends "latency test" to Eve
    Then Eve should receive the message
    And total delivery should take ≥150ms of virtual time (3 × 50ms)

  # ─── Asymmetric Links ──────────────────────────────────────

  @festival @asymmetric
  Scenario: Asymmetric link quality between peers
    Given Alice ↔ Bob with 0% forward loss and 100% reverse loss
    And encryption is disabled
    When Alice sends "can you hear me?" to Bob
    Then Bob should receive the message
    When Bob sends "yes I can!" to Alice
    Then Alice should NOT receive it (100% reverse packet loss)
