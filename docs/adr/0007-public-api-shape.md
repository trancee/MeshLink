# 0007: Public API shape and error model

Status: accepted (2026-11-01; ticket "What public API shape makes MeshLink easiest to use?"). Version: 19 (amended 2026-11-02, ADR 0013; 2026-11-03, ADR 0011, ticket "Are Swift interop and libsodium fast enough on the iPhone SE (2016; 2026-11-18, proximity)?" and ADR 0015; 2026-11-08, ADRs 0018 and 0020; 2026-11-14, lifecycle; 2026-11-14, peers; 2026-11-14, direct messages; 2026-11-15, iOS adapter; 2026-11-15, identity storage; 2026-11-15, lab link roles; 2026-11-19, routed unicast; 2026-11-20, broadcast; 2026-11-23, delivery receipts; 2026-11-25, central-only nodes; 2026-11-27, diagnostics snapshot and export).

## Context

MeshLink is used from Kotlin (Android) and from Swift (iOS, through an XCFramework built with Kotlin 2.4.21 and SKIE 0.10.16, ADR 0005). One shape must read naturally in both languages, survive additions without breaking callers, and never crash a Swift app on an error.

Prior art studied (read only; no code copied):

- **MeshLink-template:** a single `MeshLink` class with StateFlow state and a sealed exception tree, but mostly stubs. Its self-review flags:
  - an unreachable `UNINITIALIZED` state;
  - undefined flow behavior when no one collects;
  - direct links mixed up with routed reachability;
  - a lambda DSL as the only Swift construction path;
  - promises that radio behavior is identical on every platform.
- **cairn:** a single-threaded driver with one callback. Its typed, local-only close reasons and explicit delivery states (sent, queued, delivered, read, expired) proved useful in the field. It has no SKIE, so Swift switches over sealed types are not exhaustive.
- **Comparable SDKs:**
  - Nearby Connections: a status code per missing permission.
  - Bridgefy: propagation profiles, and documentation that "sent" is not "delivered".
  - Ditto v5: replaced callbacks with Flows because unclosed observers leaked; sealed exceptions with typed reasons.
  - bitchat: delivery states including carried and read; "connected" and "secure" are separate checks.
  - libp2p: one typed event enum.

SKIE facts, checked against its documentation and source on 2026-11-01:

- Suspend functions become Swift `async`, with cancellation in both directions.
- Only exceptions declared with `@Throws` become Swift errors; any other exception terminates the app. An exception thrown inside a Flow also terminates the app.
- `StateFlow.value` can be read synchronously.
- Sealed types map to exhaustive enums through `onEnum(of:)`.
- Default arguments are off and deprecated in SKIE.
- Value classes and `kotlin.Result` do not export.
- `ByteArray` arrives as `KotlinByteArray`.

Kotlin's own Swift export is alpha and doesn't support XCFramework distribution, so it is not an option.

Prototype: throwaway branch `prototype/public-api` (commits `105001d`, `f4ffd04`, `b5edba8`; `prototypes/public-api/README.md`).

- It builds a stub API over an in-memory fake mesh and implements the five reference scenarios in Kotlin (JVM, 17/17 pass) and Swift (XCTest on an iOS 18.5 simulator: 15/15 with the overlay, 6/6 without).
- The Swift scenarios need 22% more characters than Kotlin with the overlay, and 31% more without it.
- No known SKIE issue (#143, #151, #199, #208) appeared.
- Kotlin/Native 2.4.21 stamps `sdk 26.4` into the binary's build-version command from its own `konan.properties`, while it links against the installed Xcode 16.4 / iOS 18.5 SDK. This is not a toolchain violation.

## Decision

1. **Entry point:** one `MeshLink` instance per process.
   - `MeshLink.create(config, platform)` does not suspend. On Android, `platform` wraps a `Context` that the app passes explicitly.
   - A second running instance gets a typed rejection.
2. **Lifecycle:** `start()` and `stop()` suspend and are idempotent. `close()` is final.
   - `state: StateFlow<MeshState>` is one of `Stopped`, `Starting`, `Running`, `Blocked(requirements)` or `Stopping`.
   - There is no `Paused` or `Uninitialized` state.
3. **Requirements:** the app requests permissions; MeshLink never shows a permission prompt.
   - Anything missing shows as `Blocked` with a typed set: `BluetoothOff`, `PermissionDenied(kind)`, `Unsupported` (for example the iOS simulator) or `RadioInUse`.
   - MeshLink leaves `Blocked` by itself once the requirements are met.
4. **Configuration:** an immutable `MeshLinkConfig`. It is a regular class, not a data class, so adding a field doesn't break callers.
   - Both languages build it with one DSL: `meshLinkConfig(preset, securityProfile) { … }`. Swift writes `{ $0.meshId = … }`.
   - There is no separate fluent builder and no SKIE default arguments.
   - `build()` validates the config and throws a declared exception if it is invalid.
   - `describe()` prints the effective settings.
5. **Security profile:** the app must choose one; there is no default. The profiles are `SecurityProfile.Unsecured`, `Classical` and `PostQuantum`.
   - The profile called `none` in ADRs 0001 and 0002 is renamed "unsecured" everywhere, because Swift's `.none` collides with `Optional.none`.
   - Every start in `Unsecured` emits a warning diagnostic.
6. **Presets:** a sealed `Preset` with five built-ins (`OfflineChat`, `EmergencyBroadcast`, `ProximityDiscovery`, `LocalMultiplayer`, `FieldTeams`) plus `Preset.Custom(name, base, overrides)`.
   - A preset is local to a node. Only the mesh ID, security profile and mesh key must match across a mesh; a mismatch is rejected with a typed reason.
   - What each preset contains is decided by the modules ticket.
7. **Sending:** `send(to: NodeId, payload, options)` routes the message, with direct-only as an option. `broadcast(payload, hopLimit, options)` sends a mesh broadcast.
   - Neither suspends. Both return a sealed `SendResult`: `Accepted(OutgoingMessage)` or `Rejected(reason)`, where the reason is for example `TooLarge`, `QueueFull`, `NotRunning` or `UnknownPeer`.
   - An expected failure never throws.
8. **Delivery status:** `OutgoingMessage.status: StateFlow<DeliveryStatus>` is one of `Queued`, `Sent`, `Held`, `Delivered`, `Read`, `Failed(reason)` or `Expired`.
   - `Sent` means the message left this node; it does not mean `Delivered`.
   - Receipts are opt-in per message, within what the config allows.
9. **Receiving:** `messages: Flow<ReceivedMessage>` allows a single collector. A second collector gets the declared `MeshLinkException.AlreadyCollected`; apps that need fan-out use `shareIn`.
   - Messages wait in a bounded inbox until collected. `Delivered` means the app took the message.
   - A full inbox stops acknowledging, so nothing is dropped silently.
   - Resource-exhaustion defense (A4):
     - each sender may use at most ¼ of the inbox;
     - only the original sender retries, with backoff and a maximum attempt count, never past the message lifetime;
     - relaying never depends on the inbox;
     - nothing on the wire tells a sender the receiver is busy.
   - Sybil identities (A3) can get around the per-sender share in open meshes and the unsecured profile. The guarantee therefore stays "bounded resources, no delivery guarantee".
   - `ReceivedMessage.markRead()` doesn't suspend and is idempotent. It does nothing unless a read receipt was requested and is allowed.
10. **Peers:** `peers: StateFlow<List<Peer>>`. Each `Peer` has a `NodeId`, a reachability (`Direct`, `Routed(hops)` or `Unreachable`), a separate `secured` flag, a last-seen time and a `proximity`.
    - Proximity exists only for direct peers. It holds a smoothed RSSI in dBm, a bucket (`Immediate`, `Near`, `Far` or `Unknown`) and the time it was measured. It is never a distance in meters and is never sent on the wire.
    - How accurate the buckets are needs device evidence (T9). UWB is out of scope for v1.
11. **Errors:**
    - Expected outcomes are sealed result values.
    - Every Flow is exception-free.
    - Exceptions are only for caller bugs, under the sealed root `MeshLinkException`, declared with `@Throws` on every public function that can throw. Every other exception is wrapped inside MeshLink.
    - The public API has no `kotlin.Result`, no value classes and no nullable primitives (`Int?` exports as `KotlinInt?`). It uses explicit sealed alternatives instead, for example `MessageLifetime.Default` / `MessageLifetime.Of(seconds)`.
12. **Diagnostics:** `diagnostics: Flow<DiagnosticEvent>`.
    - Each event has a stable code, a severity and redacted fields.
    - Diagnostic codes use a number range separate from rejection and failure reasons.
    - The buffer is bounded and debug detail is off by default. An overflow produces a summary event.
    - Codes are closed Kotlin hierarchies documented in KDoc (ADR 0006).
13. **Threading:**
    - MeshLink can be called from any thread and does its work on its own dispatcher.
    - Swift code moves to `@MainActor` itself. Under Swift 6, collect flows from main-actor code, because SKIE marks only enums `Sendable`.
    - The public API has no generic classes with suspend members and no lambdas as type arguments.
14. **Swift overlay:** a SwiftPM Swift target of about 60 lines, idioms only, no logic (T3). It adds:
    - `Data` ↔ `KotlinByteArray` conversion;
    - Swift default arguments on the options types;
    - a static `MeshLink.create`;
    - `error.meshLink`, which gives typed access to `MeshLinkException`.
15. **Testing:** the in-memory fake mesh ships as a separate test-fixtures artifact, `meshlink-testing`, for apps' own tests. It never ships in the production framework.

## Alternatives

- **Callbacks or delegates** (Bridgefy, cairn, MultipeerConnectivity): Ditto moved away from them because of leaks. SKIE turns Flows into AsyncSequence with cancellation.
- **Exceptions for expected failures:** an undeclared exception crashes Swift, and an exception inside a Flow always does.
- **`kotlin.Result`:** doesn't export to Swift.
- **A fluent builder alongside the DSL:** two construction paths to document and test. The prototype showed the DSL closure works from Swift.
- **A data class for config:** adding a field breaks the constructor and `copy` (Kotlin API guidelines).
- **A hot, multi-collector `messages` flow:** messages are lost whenever no one is collecting (template self-review).
- **Exact distance in meters:** RSSI depends too much on the device and its surroundings.
- **No Swift overlay:** Swift code needs byte-copy loops, boxed values and NSError casts.
- **Kotlin Swift export instead of SKIE:** alpha, and it can't produce an XCFramework.

## Risks

- **SKIE** is a single-vendor compiler plugin. 0.10.16 exists only as a git tag; the latest GitHub release is 0.10.15. ADR 0005 already covers the pin.
- **Swift 6 strict concurrency:** non-`Sendable` exported classes force main-actor collection.
- **Untested costs:** `Data` conversion and flow overhead per message, and whether cancelling a Swift task reaches the Kotlin side. Benchmark both on the iPhone SE 2016 before v1.
- **A single-collector `messages` flow** surprises apps that collect it twice. The declared exception and the docs make it fail loudly.

## Migration

- ADRs 0001 and 0002 carry a terminology note for `none` → `unsecured`. The glossary, threat model, profile guarantees and tracker use "unsecured".
- The API itself arrives with the first module code (the modules ticket decides where `meshlink-testing` and the overlay live).

## Amendments

2026-11-03, modules decision (ADR 0011):

- **Testing (decision 15):** `meshlink-testing` serves Kotlin and Android tests only. Swift apps use `MeshLinkTesting`, a pure Swift target with a protocol-based fake in the `meshlink-swift` package, never linked into an app target. A second Kotlin framework beside `MeshLinkKit` is the setup the Kotlin docs call limited.
- **Swift overlay (decision 14):** lives in the separate `meshlink-swift` repository with `Package.swift`. It gains a Swift protocol that `MeshLink` conforms to, so the fake can stand in for it; it stays idioms only.
- **Presets (decision 6):** contents and mechanism are in ADR 0011.

2026-11-03, iPhone SE (2016) benchmark ([evidence](../evidence/2026-11-se2016-bench/README.md)):

- **Send capacity (amends decision 7):** `send` and `broadcast` stay non-suspending. MeshLink adds `suspend fun awaitSendCapacity()` (Swift: `try await`), which returns once the outbound queue has room again, or immediately if it already has. A caller that gets `Rejected(QueueFull)` awaits it and then sends again; it never retries `QueueFull` in a loop. The KDoc, the how-to guides and every reference scenario follow this pattern.
  - Why: a sender that retried `QueueFull` in a loop reached 380 msg/s on the SE 2016 and grew memory by about 1 GB, because its allocations outran the Kotlin garbage collector. A forced collection reclaimed most of it, so it was not a leak. A sender that kept at most 32 messages in flight reached 9,200–9,500 msg/s with flat memory.
  - Rejected: making `send` itself suspend. Every send from UI code would then need `await` or a coroutine, only to cover the rare full-queue case.
- **Swift overlay data conversion (amends decision 14 of ADR 0011):** the overlay converts between `Data` and `KotlinByteArray` with one bulk copy (`memcpy` over pinned memory), never element by element. Element-by-element copying cost about 2.6 ms at 16 KB, about 25 times a bulk copy. A benchmark in the overlay's tests guards this.
- **Measured costs:** a Swift-to-Kotlin-and-back round trip through SKIE took 95–111 µs median for 64 B to 16 KB on the SE 2016. Cancelling a Swift `Task` reached the Kotlin coroutine in 100 of 100 trials, p95 under 0.5 ms. These were the "untested costs" of this ADR.

2026-11-02, message lifecycle ([ADR 0013](0013-message-lifecycle-and-receipts.md)):

- **Delivery status (decision 8):** the exact meaning, order and terminal states of each status are in ADR 0013. `Delivered` is always reported; only read receipts are opt-in. `Failed` covers local causes only, and a message no receipt confirmed becomes `Expired` at its expiry plus 30 s.
- **Receiving (decision 9):** `ReceivedMessage` gains `readReceiptRequested`.

2026-11-03, message size ([ADR 0015](0015-message-size-and-compression.md)):

- **Limits (decision 7):** `config.limits` exposes the effective `maxUnicastPayload` and `maxBroadcastPayload`; `describe()` prints them. `TooLarge` means above the configured cap; `QueueFull` also covers the 1 MiB send-queue byte cap.
- **Inbox (decision 9):** bounded at 1 MiB as well as by count. The link-local "busy" reply of ADR 0015 concerns reassembly at the next hop, not the destination's inbox.

2026-11-08, certificate acquisition ([ADR 0018](0018-certificate-acquisition.md)):

- **Sending (decision 7):** `send` to a NodeId without a valid certificate returns `Accepted`. The status stays `Queued` while MeshLink looks the certificate up, then the message is sealed, or it becomes `Expired` at its lifetime. `Rejected(PeerKeyExpired)` is removed. `Rejected(UnknownPeer)` remains only for a malformed NodeId, or an unknown NodeId in `unsecured`.
- **Peers (decision 10):** a remote node is `Routed` only while MeshLink holds a valid certificate for it and has a route to it.
- **Certificates:** v1 has no API to import or export certificates.

2026-11-08, diagnostics catalog ([ADR 0020](0020-diagnostics-catalog.md)):

- **Diagnostics (decision 12):**
  - Codes are `ML` plus four digits, in ranges by area, with severities `Debug`, `Info`, `Warning` and `Error`.
  - Peers appear as `PeerRef`, a per-start keyed hash, or `LinkRef` before authentication.
  - Repeats within 10 s are coalesced. The buffer holds 256 events; overflow emits `ML7001`.
  - New: `diagnosticsSnapshot()`, `exportDiagnostics()` (JSON, never uploaded), and the settings `diagnostics.debug` and `diagnostics.platformLog` (both off by default).

2026-11-14, lifecycle (0.1.0 backlog ticket "Create, start, stop and close a node through the public API"):

- **Single instance (amends decision 1):** the rule is one running instance per platform radio, not per process (owner decision).
  - A second instance started on a platform radio that another running instance holds reports `Blocked(RadioInUse)`, the typed rejection, plus any other missing requirement. It runs by itself once the holder stops or closes.
  - Any number of instances run in one process on `VirtualAir`, each on its own virtual radio, so multi-node tests need no second process ([ADR 0017](0017-testing-architecture.md) decision 3).
  - Why: a per-process rule would forbid those tests. The platform radio is the shared resource, so the platform radio is what the rule guards.
- **Sealed interfaces (decisions 2, 3 and 5):** `MeshState`, `Requirement` and `SecurityProfile` are sealed interfaces, not sealed classes. detekt's `AbstractClassCanBeInterface` reports a sealed class without state, and the build allows no suppressions. In Swift their cases are top-level types: `MeshStateBlocked`, `RequirementUnsupported`, `SecurityProfileUnsecured`. `Preset` keeps state, stays a sealed class, and keeps nested names such as `Preset.OfflineChat`. The overlay's `onEnum` mapping belongs to ticket 55.
- **Platform (decision 1):** `MeshPlatform` has no public constructor. Apps get it from a platform factory: `MeshPlatform.ios()` on iOS now, the Android factory with the Android radio. `MeshPlatform.of(port)` requires the `InternalMeshLinkApi` opt-in, is hidden from Objective-C, and exists for `meshlink-testing`.
- **Not yet in place:** SKIE 0.10.16 with Kotlin 2.4.21 exports `state` to Swift as a plain Kotlin `StateFlow`, not as a Swift `AsyncSequence`; Swift reads `state.value` until the overlay ticket (55) resolves it. The warning diagnostic on an `Unsecured` start (decision 5) is in place since ticket 54a: [`ML1003`](../reference/diagnostics.md#ml1003).

2026-11-14, peers (0.1.0 backlog ticket "Open an `Unsecured` link with `PLAIN_HELLO`"):

- **No `Unreachable` (amends decision 10):** a peer leaves `peers` when its last link closes, whether it sent `CLOSE`, stopped or went out of range. `Reachability` holds only `Direct` until routing adds `Routed(hops)`. Why: `peers` lists the nodes this node can reach now; keeping unreachable entries would grow the list without bound in a busy place, and an app that wants history keeps its own.
- **Peer shape (decision 10):** `NodeId` and `Peer` are regular classes with internal constructors, not data or value classes (decision 11 rules out value classes). `NodeId` compares by content, prints as lowercase hex and returns a copy of its 32 bytes from `toByteArray()`. `lastSeen` is a `kotlin.time.Instant` from the platform's wall clock, refreshed when the link opens and on every frame received.
- **Not yet in place:** `proximity` arrives with ticket 32 (RSSI smoothing and buckets); `secured = true` with the secured profiles. (Proximity landed; see the amendment of 2026-11-18.)

2026-11-14, direct messages (0.1.0 backlog ticket "Send and receive a direct message between two nodes"):

- **No send options yet (amends decision 7):** `send(to, payload)` takes no `options` until the direct-only option changes what a send does (ticket 47) (owner decision). Until routing lands (ticket 37), every message travels only over a direct link to its destination, with `hop_limit` 1. Why: an option with no effect misleads callers, and the build allows no suppression of the unused parameter. Adding a defaulted parameter later keeps Kotlin call sites compiling; the ABI dump records the binary change. (Superseded by the amendment of 2026-11-23.)
- **Inbox count (decision 9):** the inbox holds at most 1,024 messages beside its 1 MiB and ¼-per-sender bounds. A message that would cross any bound is dropped before the app sees it; a collected message frees its room.
- **One collector at a time (decisions 9 and 11):** `messages` is the one Flow that throws, and only `MeshLinkException.AlreadyCollected` to a second collector while another is active. Once the first collector stops, a later one may collect and gets the messages still waiting. The flow completes when the node closes.
- **Send-queue cap (amendment of 2026-11-03):** `send` returns `Rejected(QueueFull)` only while the unwritten payloads already reach 1 MiB, so the queue may exceed the cap by at most one message. A message frees its bytes once written or finished.
- **Who may be addressed (amendment of 2026-11-08):** in `unsecured`, a NodeId is known once a link to it completed its hello. A node remembers the 1,024 most recently seen NodeIds across `stop()` and `start()`; any other NodeId, including the node's own, gets `Rejected(UnknownPeer)`. `send` is accepted while the node is `Running` or `Blocked`, returns `Rejected(NotRunning)` otherwise, and throws `Closed` after `close()`.

2026-11-15, iOS adapter (0.1.0 backlog ticket "Scan, advertise and carry links over GATT on iOS"):

- **Bluetooth prompt on iOS (amends decision 3):** iOS shows its Bluetooth prompt when an app first creates a `CBCentralManager` or `CBPeripheralManager`. MeshLink creates both at the first `start()`, never at `create()`, so the prompt can appear then (owner decision). An app that wants it at another moment calls `start()` then, and needs `NSBluetoothAlwaysUsageDescription` in its `Info.plist`. While authorization is not determined, the node reports `Blocked(PermissionDenied(Bluetooth))`. Until both managers report a known state, it stays `Starting`.
- **Simulator (decision 3):** the `iosSimulatorArm64` build never touches CoreBluetooth and reports `Blocked(Unsupported)`; the choice is made at compile time, not by asking the radio.

2026-11-15, identity storage (0.1.0 backlog ticket "Keep the installation identity in the platform keystore"):

- **`IdentityUnavailable` (amends decision 3):** `Requirement.IdentityUnavailable` means the stored installation identity could not be read, or a new one could not be created or saved (ADR 0003 amendment of 2026-11-15). When present it is the only requirement. It is the exception to "leaves `Blocked` by itself": the node waits, without radio work, for the app to call `start()` again, which reads the store again, or `resetIdentity()`. While it is blocked on its identity, `send` returns `Rejected(NotRunning)` (owner decision).
- **`resetIdentity()` (amends decision 2):** `suspend fun resetIdentity(): Boolean` deletes the platform's stored identity, so the next `start()` creates one with a new NodeId, which peers see as a key change. It is the only way out of an unreadable identity; MeshLink never replaces one on its own. It is allowed while the node is `Stopped` or `Blocked`, stops a `Blocked` node first, and leaves the node `Stopped`; in any other state it throws the declared `MeshLinkException.InvalidState(state)`, and after `close()` it throws `Closed` (owner decisions). It returns `false` if the platform refused to delete the identity. Other nodes on the same platform keep the old identity until they restart.

2026-11-15, lab link roles (0.1.0 backlog ticket "Drive the lab apps from `tools/lab/`"):

- **Link-role hook (internal API):** `var restrictedLinkRole: LinkRole?` and `val linkRoles: Map<NodeId, LinkRole>` on `MeshLink` need the `InternalMeshLinkApi` opt-in, which now marks properties too, and are hidden from Objective-C, like `MeshPlatform.of(port)`. Only the lab app uses them, to run a scenario in a chosen role and record the role each link took. A node restricted to `CENTRAL` neither advertises nor serves its capability record; one restricted to `PERIPHERAL` doesn't scan; `null` lifts the restriction. A restriction takes effect at the next `start()` (owner decision). The roles map is empty while the node isn't running and drops a peer when its link closes.

2026-11-18, proximity (0.1.0 backlog ticket "Apply the PHY policy, advertising and scanning rules"):

- **Proximity (decision 10):** `Peer.proximity` is a `Proximity` with `rssi: Double?`, `bucket: ProximityBucket` and `measuredAt: Instant?`, and is `null` for a peer that isn't direct (owner decision). `Proximity` is a regular class with an internal constructor; `ProximityBucket` is an enum of `Immediate`, `Near`, `Far` and `Unknown`.
- **Smoothing:** an exponentially weighted moving average, `smoothed = 0.25 × sample + 0.75 × smoothed`. The first sample, and the first after the average went stale, is taken as is.
- **Buckets:** `Immediate` at −55 dBm or more, `Near` at −75 dBm or more, otherwise `Far`. `Unknown` once the latest sample is 30 s old; `rssi` and `measuredAt` keep the last values.
- **Samples (owner decision):** the RSSI of each link this node opened as central, read every 10 s, and every sighting of a linked peer, matched by address or by the NodeId its resolvable tag resolves to. A peripheral whose central doesn't advertise, or isn't recognizable in an open mesh, may stay `Unknown`.
- **Republishing:** `peers` updates after every 10 s reading, when a sighting moves a peer to another bucket, and when a peer's sample goes stale.
- **Why:** α = 0.25 lets one sample move the average by a quarter of the step, so a single faded sample rarely changes the bucket alone, while six 10 s readings (60 s) still move it 82% of the way (1 − 0.75⁶). The thresholds are starting values without device evidence yet: ticket 33 checks the buckets on lab phone pairs and may change α, the thresholds or the staleness through a later amendment.

2026-11-19, routed unicast (0.1.0 backlog ticket "Route a unicast message over several hops"):

- **Who may be addressed (replaces the amendment of 2026-11-08):** `send` to the node's own NodeId returns `Rejected(UnknownPeer)`; any other NodeId is accepted, whether or not the node ever linked with it (ADR 0018 amendment of 2026-11-19). `send` is still accepted while the node is `Running` or `Blocked`, returns `Rejected(NotRunning)` otherwise, and throws `Closed` after `close()`.
- **Send capacity (amends the send-queue cap of 2026-11-03):** a message holds its bytes against the 1 MiB cap until it finishes: it expires, fails or, from ticket 47, is delivered. A routed message may be written many times by retries, so freeing its bytes at the first write would let the queue grow without bound (owner decision).

2026-11-20, broadcast (0.1.0 backlog ticket "Flood `Unsecured` mesh broadcasts"):

- **`broadcast` (decision 7):** `fun broadcast(payload: ByteArray, hopLimit: Int = 255): SendResult` returns at once, as `send` does. A `BroadcastOptions` class arrives with its first option, not before, so no empty class enters the ABI (owner decision).
- **One message type (owner decision):** `SendResult.Accepted.message` is the same `OutgoingMessage`, whose `to` is now `NodeId?` and `null` for a broadcast; its `status` goes `Queued`, `Sent` at the first written copy, then `Expired` at the expiry plus `clock-skew`, or `Failed(Stopped)`. Broadcasts get no receipts. Received broadcasts arrive on the one `messages` flow, under the same inbox bounds, with the new `ReceivedMessage.broadcast` `true`; a second flow would split one inbox into two to collect. The sender of an `Unsecured` broadcast is the NodeId its container claims, unauthenticated.
- **Validation (owner decision):** a `hopLimit` outside 1 to 255 returns the new `Rejected(InvalidHopLimit)`, rather than being clamped or thrown; a payload above 1,024 B returns `Rejected(TooLarge)`; `NotRunning` and `Closed` apply as for `send`; the payload counts against the 1 MiB send queue until the broadcast ends, so `QueueFull` applies.
- **No link (owner decision):** a broadcast accepted while the node has no link waits `Queued`, never `Held`, and goes out on the first link that opens.
- **Secured profiles:** until signed broadcasts land (ticket 39a), a `Classical` or `PostQuantum` broadcast stays `Queued` without a frame until it expires, and a secured node neither delivers nor relays another node's broadcast.

2026-11-23, delivery receipts (0.1.0 backlog ticket "Report delivery with delivery receipts"):

- **Send options (amends decision 7; supersedes the "No send options yet" amendment of 2026-11-14):** `send(to, payload, options = SendOptions())` takes a `SendOptions`, a regular class whose only property is `directOnly: Boolean = false` (owner decision). Kotlin callers that pass no options keep compiling; the ABI dump records the new signature. Later options, such as the read-receipt request of ticket 48, join `SendOptions` rather than `send`.
  - Why a class and not a `Boolean` parameter: options grow, and named properties on one value read better at the call site than a row of flags. Why not a data class: decision 11 keeps `copy` and `componentN` out of the public ABI.
- **`Delivered` (decision 8):** a unicast's status now becomes `Delivered` when its destination's delivery receipt arrives, in `Unsecured`. In `Classical` and `PostQuantum` no receipt is sent or taken until secured sessions land (ticket 46), so a secured message still ends `Expired`.

2026-11-25, central-only nodes (0.1.0 backlog ticket "Run phones that can't advertise MESH as central-only nodes"):

- **`Running(limitations)` (amends decision 2):** `MeshState.Running` is now `data class Running(limitations: Set<Limitation>)`, like `Blocked(requirements)`; the set is empty on a full node (owner decision). `Limitation` is a sealed interface whose only member so far is `CentralOnly(reason)`, with `CentralOnlyReason` `NoAdvertiser`, `AdvertisingFailed` or `LegacyUuidBug` (ADR 0010 amendment of 2026-11-25). A limitation isn't a requirement: the node runs, and the app can't fix it, but it can tell its user that peers won't find this phone. `== MeshState.Running` no longer compiles; callers write `is MeshState.Running`. This is a source and binary break, allowed before 1.0 (ADR 0019).
  - Why state and not only a diagnostic (ADR 0020): an app reads `state` already and must learn that the phone is reachable only by its own connections for as long as it is; a diagnostic is an event that a late collector misses.
- **`AdvertisingFailed` lasts the run (owner decision):** after an advertising start or a running advertisement fails, the node stays central-only, with the reason in `state`, until the run ends: the next `start()`, Bluetooth turning off and on, or any requirement that blocks and clears. It doesn't retry within the run, so a failing stack isn't hammered.
- **Link-role hook (amends the amendment of 2026-11-15):** a central-only node is a central whatever `restrictedLinkRole` says, even `PERIPHERAL`, because it can't be found.

2026-11-27, diagnostics snapshot and export ([ADR 0020](0020-diagnostics-catalog.md) amendment of 2026-11-27, ticket 54d):

- **Diagnostics (amends decision 12 and the amendment of 2026-11-08):** `MeshLink.diagnostics` is a `MeshDiagnostics`. Its `events` is the `Flow<DiagnosticEvent>` that `diagnostics` was, and it adds `suspend fun snapshot(): DiagnosticsSnapshot` and `suspend fun export(): String` in place of `diagnosticsSnapshot()` and `exportDiagnostics()`. Both are callable in any state, also after `close()`. The rename touches only unreleased API.
  - Why one type: detekt allows `MeshLink` 11 functions and the project suppresses no finding; grouping the diagnostics also keeps `MeshLink`'s own surface about the mesh.
