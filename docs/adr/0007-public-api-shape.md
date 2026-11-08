# 0007: Public API shape and error model

Status: accepted (2026-11-01; ticket "What public API shape makes MeshLink easiest to use?"). Version: 7 (amended 2026-11-03, ADR 0011, ticket "Are Swift interop and libsodium fast enough on the iPhone SE (2016)?", ADR 0013 and ADR 0015; 2026-11-08, ADRs 0018 and 0020).

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
