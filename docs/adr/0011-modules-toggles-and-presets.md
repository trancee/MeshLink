# 0011: Modules, feature toggles and presets

Status: accepted (2026-11-03; ticket "How are modules cut, and how do feature toggles and presets work?"). Version: 2 (amended 2026-11-02, ADR 0013).

## Context

MeshLink must be modular and configurable, yet easy to use. Earlier decisions constrain how:

- **Relaying is universal (ADR 0008):** every node receives and relays every frame type, and density classes change only local forwarding behavior. A feature can therefore never be optional on the wire.
- **Compatibility (ADR 0007):** only the mesh ID, security profile and mesh key must match across a mesh; presets are local to a node.
- **Crypto (ADR 0004):** libsodium is the only native dependency (static, symbols hidden, at most about 1 MB per Android ABI). Every other feature (routing, broadcast, receipts, store-and-forward, L2CAP CoC) is pure Kotlin, so splitting it into its own artifact saves no dependency and little size.
- **Thin platform code (Constitution C2, T3):** logic lives in common code; platform code is adapters.
- **Simplicity (Constitution D1, D2):** no speculative extension points or splits.

Verified sources:

- Kotlin docs, [Swift package export setup](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html): several Kotlin modules for Swift must be combined into one umbrella module, exported as one XCFramework. The recommended layout keeps `Package.swift` in a separate Git repository, because SwiftPM versions packages by Git tags, which can conflict with the project's own tags.
- Kotlin docs, [Apple framework export](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html): "Usage of several Kotlin/Native frameworks in a Swift application is limited"; the remedy is an umbrella framework that exports the modules. Exporting a dependency disables dead-code elimination for it.
- Apple, [Distributing binary frameworks as Swift packages](https://developer.apple.com/documentation/xcode/distributing-binary-frameworks-as-swift-packages): a remote `binaryTarget` points to a ZIP archive with the XCFramework at its root, verified by a checksum from `swift package compute-checksum`.

Consequence of the second source: a separate `meshlink-testing` Kotlin framework next to the production framework is the limited setup the docs warn against; putting it inside the umbrella would ship test code in production (Constitution E5). ADR 0007 decision 15 is amended below.

## Decision

1. **Published artifacts (v1):**

   | Artifact | Channel | Contents |
   |---|---|---|
   | `meshlink` | Maven | Every feature and the libsodium binding (Android and Kotlin/Native klibs) |
   | `meshlink-testing` | Maven | The in-memory fake mesh for Kotlin and Android tests |
   | `MeshLinkKit.xcframework` | ZIP release asset | One umbrella framework with every feature and libsodium |
   | `MeshLink` Swift package | `meshlink-swift` repository | `binaryTarget` for the XCFramework, the Swift overlay and `MeshLinkTesting` |

   There is no crypto-free artifact and no per-feature artifact in v1. The internal module boundaries (decision 2) allow a later split without breaking callers.

2. **Internal Gradle modules:**

   | Module | Holds | Depends on |
   |---|---|---|
   | `:meshlink-core` | Platform-free logic: protocol, routing, density, Noise, `CryptoProvider` interface, config and presets | Kotlin standard library, kotlinx.coroutines |
   | `:meshlink-crypto-sodium` | The libsodium binding (JNI and cinterop) and its native build | `:meshlink-core` |
   | `:meshlink` | Android and iOS adapters (BLE, lifecycle) and the public API; the published umbrella | `:meshlink-core`, `:meshlink-crypto-sodium` |
   | `:meshlink-testing` | The fake mesh | `:meshlink` |

   `:meshlink-core` and `:meshlink-crypto-sodium` are not published on their own. A build check enforces the dependency direction; the tool is chosen with the first module and needs an E3 row.

3. **Noise:** no standalone Noise artifact in v1. Noise lives in `:meshlink-core` behind `CryptoProvider` as internal API.

4. **Swift testing:** `meshlink-testing` serves Kotlin and Android tests only. Swift apps get `MeshLinkTesting`, a pure Swift SwiftPM target with a protocol-based fake. It contains no Kotlin and is never linked into an app target. A test-variant XCFramework was rejected: it doubles the binary hosting, CI and versions.

5. **Swift package location:** `Package.swift`, the Swift overlay and `MeshLinkTesting` live in a separate repository, `meshlink-swift`. The main repository's release publishes the XCFramework ZIP and checksum; CI then updates `meshlink-swift`. Development uses a path-based `Package.swift` under `swift/` in the main repository. Creating the repository needs explicit approval.

6. **Build logic:** an included build, `build-logic/`, holds the convention plugins (Kotlin Multiplatform targets, detekt, Kover, Dokka, publishing) and the docs and governance tasks of ADR 0006 (`checkDependencyRegister`, region sync, reference generation).

7. **Hashing in the unsecured profile:** every profile links libsodium (decision 1), so mesh tags use libsodium's BLAKE2b in every profile. There is no platform SHA-256 path.

8. **Toggle rule:** a toggle governs only local origination, holding of other nodes' messages, or local radio use. It never changes relaying, parsing or the wire format: a node with a feature off still parses and relays that feature's frames.

9. **No relay-off mode:** v1 has no leaf or relay-off mode, because it would break ADR 0008's assumption that every node relays, on which the simulation evidence rests. Revisit only with new simulation evidence.

10. **Toggles (v1):**

    | Toggle | Default | Notes |
    |---|---|---|
    | Routed unicast | on | Off means direct-only sending |
    | Broadcast origination | on | Relaying is unaffected |
    | Receipts requested | from the preset | Per-send options override it; semantics are decided later |
    | Holding for others | from the preset | ADR 0008 limits apply |
    | L2CAP CoC upgrade | on | ADR 0009; Android 8 and 9 stay GATT only |
    | Extended advertising | off | ADR 0010 |
    | iOS Live Activity hook | off | Opt-in; the host app owns the activity (ADR 0010) |
    | Density | automatic | An app may pin a class (ADR 0008) |
    | Default message lifetime | from the preset | Validation rejects values under 120 s |

11. **Preset mechanism:** a preset is a named set of default values. The DSL block overrides them, and validation runs on the merged result. `describe()` prints every effective value with its source: `preset`, `override` or `default`. The preset reference table is generated from code (ADR 0006).

12. **Built-in presets (draft):** only the decided knobs. Lifetime, size cap and power values are filled in by later tickets.

    | Preset | Density | Sending | Holding | Receipts |
    |---|---|---|---|---|
    | `OfflineChat` | automatic | unicast and broadcast | on | on |
    | `EmergencyBroadcast` | automatic | broadcast first, unicast on | on | off |
    | `ProximityDiscovery` | automatic | direct-only unicast | off | off |
    | `LocalMultiplayer` | pinned low | unicast and broadcast | off | off |
    | `FieldTeams` | automatic | unicast and broadcast | on | on |

13. **Background behavior:** not a preset value. The host app owns background modes and Android foreground services; MeshLink documents per-preset recommendations in a how-to guide. A preset never requests permissions or modes.

## Alternatives

- **One artifact per feature (routing, broadcast, store-and-forward):** every feature's frames must be parsed and relayed anyway (ADR 0008), so the code ships regardless; the split only adds versions to align.
- **A crypto-free artifact for the unsecured profile:** saves libsodium's size, but doubles the iOS framework and the test matrix. Deferred until an app asks for it; decision 2 keeps the split possible.
- **A standalone Noise artifact:** no consumer needs it in v1, and publishing it freezes an internal API.
- **A second Kotlin framework for Swift tests:** the limited multi-framework setup the Kotlin docs warn against.
- **A test-variant XCFramework:** works, but doubles binary hosting, CI and versioning.
- **`Package.swift` at the main repository's root:** simpler, but SwiftPM tags then share the project's tag namespace, and SwiftPM clones the whole Kotlin repository.
- **A leaf or relay-off mode:** saves battery on a few nodes, but invalidates the routing evidence.
- **Presets that fix a density class:** rejected by ADR 0008; only `LocalMultiplayer` pins low, for latency.

## Risks

- **Size without crypto:** unsecured-only apps carry libsodium. Mitigation: measure the size with the first build; split later if it matters.
- **Swift fake drift:** `MeshLinkTesting` is hand-written Swift and can diverge from the Kotlin fake. Mitigation: both run the same reference scenarios (ADR 0007) in CI.
- **Two repositories:** a release must update `meshlink-swift` atomically with the XCFramework ZIP. Mitigation: one release workflow does both, and a smoke test resolves the published package.
- **Draft presets:** values depend on open tickets (receipts, store-and-forward, size cap, power).

## Consequences

- The first Gradle code creates the four modules of decision 2 and `build-logic/`.
- The Swift overlay of ADR 0007 decision 14 gains a Swift protocol that `MeshLink` conforms to, so `MeshLinkTesting` can fake it. The overlay stays idioms only (T3).
- The receipts, store-and-forward, message size and power tickets fill the preset table; the generated reference replaces the draft.
- The release ticket designs the two-repository release and the `meshlink-swift` smoke test.

## Amendments

2026-11-02, message lifecycle ([ADR 0013](0013-message-lifecycle-and-receipts.md)):

- **Toggles (decision 10):** "Receipts requested" becomes two values, `readReceipts.requestByDefault` and `readReceipts.send`. Delivery receipts are always on.
- **Presets (decision 12):** the Receipts column means read receipts, with both values on for `OfflineChat` and `FieldTeams` and off for the rest.
