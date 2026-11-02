# Map: MeshLink v1

Label: `wayfinder:map`

## Destination

An approved v1 specification bundle ready to hand off as implementation tickets: `PROJECT.md` profile, `GLOSSARY.md`, ADRs for every hard decision, an implementation-independent versioned protocol spec (with conformance vectors), module + public API design, scenario/preset matrix, dependency register, documentation architecture (human + AI-optimized), and toolchain/CI plan. Prototypes are throwaway evidence only.

## Notes

- **Product:** Kotlin Multiplatform library (Android + iOS only) for BLE peer-to-peer meshes: discovery, direct and routed messages, mesh broadcast, optional delivery/read receipts, optional store-and-forward, stable at low/medium/high density. Own open protocol; MeshLink is its reference implementation. Apache-2.0.
- **Security profiles:** `unsecured` (plaintext, unauthenticated, loudly labeled), `classical`, `post-quantum`; one per mesh, mismatches rejected, never downgraded.
- **Reference scenarios → presets:** offline chat; disaster/emergency broadcast; proximity discovery; local session/multiplayer; field/industrial teams.
- **Provisional density classes** (local density, peers in radio range): low 2–10, medium 10–50, high 50–200; whole mesh ≤ ~1,000 reachable nodes. Not claims until simulated/measured (P6). Density is per node and dynamic: each node estimates its own class and adapts (routing simulation, stage C).
- **Platforms:** Android API 26+, iOS 15+; foreground guaranteed, background best-effort. Distribution: Maven Central + XCFramework via SwiftPM. Optional features ship as separate modules where a dependency/size difference exists.
- **Governance:** read `CONSTITUTION.md` (4.0.1, a MeshLink fork), `AGENTS.md`, `GLOSSARY.md`, and `KOTLIN.md` for Kotlin work, first. Every dependency needs an E3 rationale and user approval; Rust only via the Rust-approval ticket. Verify claims against primary sources; never assume — ask with options + recommendation.
- **Evidence bar:** radio decisions need real-device evidence across Android↔Android, Android↔iOS, iOS↔iOS. Device lab: 29 Android phones (`../android-devices/devices.md`); iPhones on this Mac (`xcrun devicectl list devices` for iOS 17+, `idevice_id -l` for iOS 15/16; inventory in the same devices.md). Xcode 16.4 is a hard constraint.
- **Prior art (study only, never copy code):** `../MeshLink-template` (SPEC.md, `docs/explanation/template-design-review.md` = its self-critique), `../cairn` (`docs/agents/lessons.md`, ADRs 0001/0003/0025/0038–0042, `docs/research/`).
- **Skills to consult:** grilling + domain-modeling (always for HITL); research; prototype; ble-protocol-stack, ble-throughput, android-ble*, corebluetooth, noise-protocol, kotlin-multiplatform, kotlin-gradle, skie, ubique-uniffi, robolectric, diataxis, wycheproof as relevant.
- **Research findings** live in `docs/research/` on `main`; new research lands on a `research/<slug>` branch and is merged into `main` when its ticket resolves.

## Decisions so far

- [What do existing BLE P2P meshes teach us?](issues/01-prior-art-ble-meshes.md): SIG Mesh can't be used phone-to-phone, so MeshLink needs its own dual-role protocol. Borrow managed flooding and learned next-hop routing, authenticate before relay or cache, rotate on-air identifiers, and never negotiate a downgrade.
- [What does each platform's BLE stack support in 2026?](issues/02-platform-ble-capabilities.md): GATT works everywhere; L2CAP CoC is available on both platforms (Android 29+, iOS 11+), with its port exchanged over GATT; Android↔iOS CoC is unproven, so the device-lab bake-off decides it. Discovery uses a legacy UUID-only advert plus an optional 2M extended advert. Expect about 5–6 Android links per phone; the 512 B attribute cap holds. Background is best-effort, and the BLE address is never an identity.
- [What is the most modern stable KMP toolchain in 2026?](issues/05-kmp-toolchain-2026.md): candidate baseline is Kotlin 2.4.21, AGP 9.3.x, Gradle 9.7, SKIE, SwiftPM only (CocoaPods trunk freezes 2026-12-02), Robolectric 4.17, Kover and Dokka 2.2. detekt 2 and ABI validation aren't stable yet, and Kover can't cover iOS code.
- [Is the device lab ready for cross-platform radio evidence?](issues/06-device-lab-readiness.md): all five iPhones are ready (iOS 15.8, 16.7, 17.6, 18.7, 27.0); iOS 15/16 are driven through libimobiledevice. Xcode 16.4 is a hard constraint: smoke-test the newest Kotlin and fall back to 2.2.20. No CryptoKit post-quantum crypto on iOS.
- [Which constitution amendments should MeshLink adopt?](issues/07-constitution-amendments.md): MeshLink forks the constitution as 3.1.0. T9 now requires an evidence record for hardware claims; C10 makes the protocol spec and its vectors authoritative; O8 requires a human and an agent docs layer from one source. Kotlin guidance lives in root `KOTLIN.md`, and other stack guides follow the root `<STACK>.md` pattern. The T3 iOS coverage question waits for the toolchain baseline.
- [What is the threat model, and what does each security profile guarantee?](issues/08-threat-model.md): adversaries A1–A7 are in scope; the host app is trusted. Secured profiles use authenticated links plus end-to-end encryption, open or mesh-key-gated admission, Ed25519 broadcast signatures and per-hop reassembly. Relays see only rotating routing tags; on-air identifiers rotate. Post-quantum covers confidentiality only; there is no forward secrecy for store-and-forward. Resources are always bounded and delivery is never guaranteed. Recorded in ADR 0001.
- [What handshake and crypto suite does each security profile use?](issues/09-security-profile-suites.md): a Noise suite of X25519, ChaChaPoly and SHA-256, never negotiated. Links use XX (XXpsk3 when gated, XXhfs with ML-KEM-768 for post-quantum); end-to-end uses IK with a 0-RTT first message. Post-quantum first and held messages are also sealed with HPKE/X-Wing. An Ed25519 identity certifies rotating static keys. Recorded in ADRs 0002 and 0003; first-message latency is a tracked metric.
- [Which crypto provider is approved, and is Rust part of it?](issues/10-rust-approval.md): libsodium 1.0.22 (with ML-KEM-768 and X-Wing) through our own static, symbol-hidden binding. Noise, HPKE and the certificate are in Kotlin common code. Rust is approved only as a test-only reference implementation for vectors. X-Wing is pinned to hpke-pq-05 0x647a; ≤ 1 MB per ABI. An offer to upstream HPKE to libsodium waits for approval. Recorded in ADR 0004.
- [What is the toolchain and dependency baseline?](issues/11-toolchain-baseline.md): Kotlin 2.4.21 + Gradle 9.7.1 + AGP 9.3.3 on Xcode 16.4 (no fallback needed); SKIE 0.10.16; E2 holds on detekt alpha, built-in ABI validation and Kover Beta. Targets: Android arm64/armv7/x86_64; iOS device plus simulator (radio tests on hardware only). Constitution 4.0.0: 100% coverage only where a CI tool exists, and iOS code is thin adapters. libsodium uses unmodified `-Os` upstream builds, AES never exposed. Pinned test-only Rust reference with snow#210. Dependency register checked in CI; GitHub Actions; one shared lab bundle ID on the free Apple teams. Recorded in ADR 0005.
- [What documentation architecture serves humans and AI agents?](issues/16-documentation-architecture.md): Diátaxis folders plus `docs/spec/` (BCP 14 Markdown, Wycheproof-style JSON vectors) as the authority after the Constitution; ADRs carry rationale only. Each fact has one source: protocol data in spec files, API facts (config, diagnostics, errors) in Kotlin with KDoc; reference pages and the agent JSON are generated and CI-diffed. `AGENTS.md` is the only agent file; `llms.txt` only once a site exists. Kotlin and Swift examples come from compiled tests. Dokka HTML on Pages, no site generator until release. Docs CI: lychee, markdownlint-cli2, typos (US English). Recorded in ADR 0006.
- [What public API shape makes MeshLink easiest to use?](issues/15-public-api-shape.md): one `MeshLink` per process with `StateFlow` state and peers, a typed `Blocked` state for missing permissions or Bluetooth, and non-suspend `send` and `broadcast` returning sealed results with a per-message `DeliveryStatus`. Messages wait in a bounded, fair-share inbox for one collector. Config is one DSL with sealed presets (custom allowed); the security profile is required, and `none` is renamed `unsecured`. Expected failures are values; Flows never throw. Swift uses SKIE plus a thin overlay; a `meshlink-testing` fake mesh ships separately. Prototype passes in Kotlin and Swift. Recorded in ADR 0007.
- [Which routing and topology strategy should MeshLink use?](issues/14-routing-topology-simulation.md): HyParView-style views with a diversity preference (d = 6/5/5, bridge rule, iOS incoming links ≤ 3). Each node estimates its own local density and adapts locally: flooding at low density; U6 unicast with ack-refreshed routes and Plumtree broadcast with catch-up at medium and high. The wire format is the same everywhere. Minimum lifetime is 120 s, the routing tag 8 B, the message ID 12 B, with 15 min rotation. All simulated. Recorded in ADR 0008.
- [Which crypto options fit each security profile?](issues/03-crypto-options.md): no secured profile can rely on platform crypto alone at Android 26 / iOS 15. Classical fits standard Noise (XX, then IK/KK; every message <100 B). Post-quantum means hybrid X25519 + ML-KEM-768 with classical authentication, via Noise hfs (~2.4 KB) or a cairn-style pairing plus a 106 B resume, and always needs fragmentation. Candidate providers: libsodium, Rust aws-lc-rs, or a platform split.
- [Which routing and topology strategies suit phone BLE meshes?](issues/04-routing-topology-strategies.md): with only 3–6 links per phone, neighbor selection matters as much as routing, and mesh-wide flooding won't scale to ~1,000 nodes without a hop limit. The simulation shortlist covers neighbor selection, unicast, broadcast and store-and-forward strategies.

## Not yet specified

- Wire format and codec: now [ticket 18](issues/18-wire-format-codec.md), with Kompact as the candidate codec.
- Delivery/read receipt semantics and message lifecycle states.
- Store-and-forward design (quota, TTL, custody, privacy of held messages; candidate bounded-lifetime store-and-forward keys for forward secrecy). Routing-level holding is decided (ADR 0008): 100 messages, 10 s minimum hold, 120 s minimum lifetime, ±30 s clock skew.
- Message size cap.
- Resisting fake identities (Sybil) in open and unsecured meshes: in simulation, 30% fake identities break the mesh apart under any neighbor selection strategy. Possible defenses: identity cost or peer scoring (routing simulation stage A).
- Swift interop cost before v1: per-message `Data` conversion and Flow overhead, and Swift-task cancellation reaching Kotlin, benchmarked on the iPhone SE (2016) (ADR 0007).
- Routing power and iOS background behavior on real devices: the simulation's power index and iOS wake-window model are assumptions; measure before final v1 defaults (routing simulation stage C).
- Power policy across presets and density.
- Diagnostics and troubleshooting surface: the code catalog (which codes, which fields) and field tooling. How codes are documented is decided (ADR 0006).
- Testing architecture (virtual multi-node transport, Robolectric, device-lab automation, fuzzing).
- Conformance vector coverage and the vector-runner design; the spec format is decided (ADR 0006).
- Release, versioning and API-compatibility process.
- CI integration with the device lab.
- GitHub repository creation and first workflows (ADR 0005); creating the remote needs explicit approval.
- libsodium iPhone benchmark on the iPhone SE (2016) as T9 evidence (ADR 0005), once the shared lab bundle ID is set up.
- Neighbor-list privacy: link encryption hides route messages from passive outsiders in the secured profiles (threat model); still open is what neighbor and route information members learn.
- libp2p post-quantum Noise watch (ADR 0002 amendment): track specs#727 and snow#210; adopt published `XXhfs` vectors when they appear, and return any divergence from Noise HFS to the user.
- libsodium HPKE upstream offer (ADR 0004): draft an "Ideas" thread once MeshLink's HPKE passes the hpke-pq vectors; posting needs explicit approval.
- Compression: none vs bounded (Bridgefy gzip-bomb and side-channel lessons).
- Energy measurements (cost per KB and per connection-hour on lab phones) to back battery claims.
- Shared cross-platform limits table, enforced by conformance tests.
- Optional non-BLE bearers (Wi-Fi Aware, Multipeer Connectivity, Nearby Connections) for throughput, with BLE as baseline; may belong in Out of scope.

## Out of scope

- Post-quantum signatures in v1: ruled out by the [threat model](issues/08-threat-model.md). Signatures stay classical (Ed25519); SQIsign is the leading candidate for a later suite version once NIST standardizes it.
- Encrypted named groups (group key agreement, e.g. MLS).
- File transfer and streaming.
- Targets other than Android and iOS.
- Wire compatibility with Bluetooth SIG Mesh, bitchat, Briar or other meshes.
- Multi-device users and account sync.
- Precise distance via UWB (Nearby Interaction, Android UWB): possible later optional module; v1 offers only coarse proximity for direct peers.
