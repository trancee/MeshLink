# What public API shape makes MeshLink easiest to use?

Type: prototype
Status: resolved
Blocked by: 11

## Question

Prototype Kotlin and Swift API stubs for the five reference scenarios: lifecycle, configuration DSL and presets, peers, send/receive, receipts, errors, diagnostics. Judge ergonomics from both languages. Decide the API shape and error model.

## Scope additions (from decisions)

- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): the Swift side goes through SKIE 0.10.16 (suspend → async, Flow → AsyncSequence, sealed/enum → Swift enums; default arguments are off). The iOS simulator must report "Bluetooth unsupported" through the normal state API. The KGP `abiValidation` dump becomes the API baseline.
- From the [documentation architecture](16-documentation-architecture.md) (ADR 0006): configuration options, diagnostic codes and error types are closed Kotlin hierarchies with KDoc, because reference pages and the agent JSON are generated from them. Each diagnostic code needs a stable ID plus cause, remedy and related setting. Every public API needs a Kotlin `@sample` in `commonTest` and a Swift example in the SwiftPM consumer tests.

## Design (accepted 2026-11-01)

Grilling round 1 (Q1–Q15) and follow-ups (6a, 9a, 9b, 10a, 10b) were all accepted. Prior art: MeshLink-template (including its self-review), cairn, Nearby Connections, MultipeerConnectivity, Ditto v5, Bridgefy, bitchat, libp2p; SKIE 0.10.16 behavior verified from docs and source.

- **Entry point:** one `MeshLink` per process. `MeshLink.create(config, platform)` does not suspend; on Android, `platform` wraps an explicit `Context`. A second running instance gets a typed rejection.
- **Lifecycle:**
  - `start()` and `stop()` are suspend and idempotent.
  - `state: StateFlow<MeshState>` = `Stopped`, `Starting`, `Running`, `Blocked(requirements)`, `Stopping`. No `Paused` and no `Uninitialized`.
- **Requirements:** the app requests permissions. Anything missing shows as `Blocked` with a typed set (`BluetoothOff`, `PermissionDenied(kind)`, `Unsupported`, `RadioInUse`), and the node resumes automatically once it is met.
- **Configuration:** immutable `MeshLinkConfig`, a regular class, not a data class.
  - Kotlin uses a DSL; Swift uses a fluent builder.
  - No SKIE default arguments.
  - Validation happens at `build()`.
  - The security profile is required, with no default; `unsecured` emits a warning diagnostic at every start.
- **Presets:** a sealed type of five built-ins (`OfflineChat`, `EmergencyBroadcast`, `ProximityDiscovery`, `LocalMultiplayer`, `FieldTeams`) plus `Preset.Custom(name, base, overrides)`.
  - Presets are local only. Nodes with different presets share a mesh; only the mesh ID, security profile and mesh key must match, and a mismatch is rejected with a typed reason.
  - `MeshLinkConfig.describe()` prints the effective settings. Reference tables are generated for the built-ins; preset contents are decided in the modules ticket.
- **Sending:** `send(to: NodeId, payload, options)` (routed, with direct-only as an option) and `broadcast(payload, hopLimit, options)`.
  - Both are non-suspend and return a sealed `SendResult`: `Accepted(OutgoingMessage)` or `Rejected(reason)`.
- **Delivery status:** `OutgoingMessage.status: StateFlow<DeliveryStatus>` = `Queued`, `Sent`, `Held`, `Delivered`, `Read`, `Failed(reason)`, `Expired`.
  - `Sent` is not `Delivered`. Receipts are opt-in per message, within what the config allows.
- **Receiving:** `messages: Flow<ReceivedMessage>` with a bounded inbox; nothing is dropped silently.
  - `Delivered` means the app took the message.
  - A full inbox stops acknowledging.
  - A4 mitigations:
    - per-sender fair share of at most ¼ of the inbox (anything over is dropped with a diagnostic and no acknowledgement);
    - only the sender retries, with backoff and a maximum number of attempts, never past the message lifetime;
    - relaying never depends on the inbox;
    - no "busy" signal on the wire.
  - Sybil identities defeat the per-sender share in open meshes and the `unsecured` profile (A3). The guarantee stays "bounded resources, no delivery guarantee"; gated meshes reduce the exposure.
- **Peers:** `peers: StateFlow<List<Peer>>`. Each `Peer` has a `NodeId`, a reachability (`Direct`, `Routed(hops)` or `Unreachable`), a separate `secured` flag, a last-seen time and a `proximity`.
  - Proximity applies to direct peers only: smoothed RSSI in dBm, a bucket (`Immediate`, `Near`, `Far`, `Unknown`) and the measurement time.
  - No meters. It stays local and is never sent on the wire.
  - Bucket accuracy needs T9 evidence. UWB is out of scope for v1.
- **Errors:**
  - Expected outcomes are sealed results.
  - Every Flow is exception-free.
  - Exceptions only for caller bugs, under `@Throws(MeshLinkException::class)` with a sealed root; everything else is wrapped internally.
  - No `kotlin.Result` and no value classes in the public API.
- **Diagnostics:** `diagnostics: Flow<DiagnosticEvent>`.
  - Each event has a stable code, a severity and redacted fields.
  - Diagnostic codes use a number range separate from error reasons.
  - The buffer is bounded, debug detail is off by default, and overflow produces a summary event.
- **Threading:** callable from any thread; MeshLink uses its own dispatcher. Swift moves to `@MainActor` itself.
  - The public API has no generic classes with suspend members and no lambdas as type arguments.
- **Swift:** SKIE plus a minimal Swift overlay target (`Data` ↔ `KotlinByteArray`, config conveniences) with no logic. The prototype checks whether the overlay is needed.
- **Prototype:**
  - Stub API over an in-memory fake mesh, on throwaway branch `prototype/public-api`.
  - The five scenarios in Kotlin (JVM tests) and Swift (XCTest against the SKIE XCFramework, simulator), compiled and run.
  - The outcome becomes an API ADR.

## Answer

Recorded in [ADR 0007](../../../docs/adr/0007-public-api-shape.md); every recommendation from both grilling rounds was accepted. The prototype is on throwaway branch `prototype/public-api` (`105001d`, `f4ffd04`, `b5edba8`; README has the findings).

- **Shape:** one `MeshLink` per process, with `StateFlow` state and peers and a single-collector `messages` Flow.
  - Non-suspend `send` and `broadcast` return a sealed `SendResult`.
  - `DeliveryStatus` is a `StateFlow` per message.
  - The `Blocked` state lists typed requirements.
- **Config:** one DSL for Kotlin and Swift. Presets are a sealed type with custom presets; the security profile is required and its "no protection" value is named `Unsecured`.
- **Errors:** sealed results for expected outcomes, exception-free Flows, a declared `@Throws` sealed root for caller bugs. No `kotlin.Result`, value classes or nullable primitives.
- **Swift:** SKIE plus a roughly 60-line idioms-only overlay (`Data`, defaults, static create, typed error access).
- **Prototype evidence:** Kotlin 17/17 and Swift 15/15 + 6/6 pass; Swift is 22% longer with the overlay and 31% without.
- **Applied now:**
  - `none` → `unsecured` in the glossary, threat model, guarantees and tracker, with terminology notes in ADRs 0001 and 0002.
  - Glossary terms "Proximity" and the broadened "Preset".
  - The UWB out-of-scope line.
