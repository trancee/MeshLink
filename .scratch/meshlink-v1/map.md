# Map: MeshLink v1

Label: `wayfinder:map`

## Destination

An approved v1 specification bundle ready to hand off as implementation tickets: `PROJECT.md` profile, `GLOSSARY.md`, ADRs for every hard decision, an implementation-independent versioned protocol spec (with conformance vectors), module + public API design, scenario/preset matrix, dependency register, documentation architecture (human + AI-optimized), and toolchain/CI plan. Prototypes are throwaway evidence only.

## Notes

- **Product:** Kotlin Multiplatform library (Android + iOS only) for BLE peer-to-peer meshes: discovery, direct and routed messages, mesh broadcast, optional delivery/read receipts, optional store-and-forward, stable at low/medium/high density. Own open protocol; MeshLink is its reference implementation. Apache-2.0.
- **Security profiles:** `none` (plaintext, unauthenticated, loudly labelled), `classical`, `post-quantum`; one per mesh, mismatches rejected, never downgraded.
- **Reference scenarios → presets:** offline chat; disaster/emergency broadcast; proximity discovery; local session/multiplayer; field/industrial teams.
- **Provisional density classes** (peers in radio range): low 2–10, medium 10–50, high 50–200; whole mesh ≤ ~1,000 reachable nodes. Not claims until simulated/measured (P6).
- **Platforms:** Android API 26+, iOS 15+; foreground guaranteed, background best-effort. Distribution: Maven Central + XCFramework via SwiftPM. Optional features ship as separate modules where a dependency/size difference exists.
- **Governance:** read `CONSTITUTION.md` (3.1.0, a MeshLink fork), `AGENTS.md`, `GLOSSARY.md`, and `KOTLIN.md` for Kotlin work, first. Every dependency needs an E3 rationale and user approval; Rust only via the Rust-approval ticket. Verify claims against primary sources; never assume — ask with options + recommendation.
- **Evidence bar:** radio decisions need real-device evidence across Android↔Android, Android↔iOS, iOS↔iOS. Device lab: 29 Android phones (`../android-devices/devices.md`); iPhones on this Mac (`xcrun devicectl list devices` for iOS 17+, `idevice_id -l` for iOS 15/16; inventory in the same devices.md). Xcode 16.4 is a hard constraint.
- **Prior art (study only, never copy code):** `../MeshLink-template` (SPEC.md, `docs/explanation/template-design-review.md` = its self-critique), `../cairn` (`docs/agents/lessons.md`, ADRs 0001/0003/0025/0038–0042, `docs/research/`).
- **Skills to consult:** grilling + domain-modeling (always for HITL); research; prototype; ble-protocol-stack, ble-throughput, android-ble*, corebluetooth, noise-protocol, kotlin-multiplatform, kotlin-gradle, skie, ubique-uniffi, robolectric, diataxis, wycheproof as relevant.
- **Research findings** live on local `research/<slug>` branches (worktrees, committed, not pushed); tickets link the branch + file.

## Decisions so far

- [What do existing BLE P2P meshes teach us?](issues/01-prior-art-ble-meshes.md): SIG Mesh can't be used phone-to-phone, so MeshLink needs its own dual-role protocol. Borrow managed flooding and learned next-hop routing, authenticate before relay or cache, rotate on-air identifiers, and never negotiate a downgrade.
- [What does each platform's BLE stack support in 2026?](issues/02-platform-ble-capabilities.md): GATT works everywhere; L2CAP CoC is available on both platforms (Android 29+, iOS 11+), with its port exchanged over GATT; Android↔iOS CoC is unproven, so the device-lab bake-off decides it. Discovery uses a legacy UUID-only advert plus an optional 2M extended advert. Expect about 5–6 Android links per phone; the 512 B attribute cap holds. Background is best-effort, and the BLE address is never an identity.
- [What is the most modern stable KMP toolchain in 2026?](issues/05-kmp-toolchain-2026.md): candidate baseline is Kotlin 2.4.21, AGP 9.3.x, Gradle 9.7, SKIE, SwiftPM only (CocoaPods trunk freezes 2026-12-02), Robolectric 4.17, Kover and Dokka 2.2. detekt 2 and ABI validation aren't stable yet, and Kover can't cover iOS code.
- [Is the device lab ready for cross-platform radio evidence?](issues/06-device-lab-readiness.md): all five iPhones are ready (iOS 15.8, 16.7, 17.6, 18.7, 27.0); iOS 15/16 are driven through libimobiledevice. Xcode 16.4 is a hard constraint: smoke-test the newest Kotlin and fall back to 2.2.20. No CryptoKit post-quantum crypto on iOS.
- [Which constitution amendments should MeshLink adopt?](issues/07-constitution-amendments.md): MeshLink forks the constitution as 3.1.0. T9 now requires an evidence record for hardware claims; C10 makes the protocol spec and its vectors authoritative; O8 requires a human and an agent docs layer from one source. Kotlin guidance lives in root `KOTLIN.md`, and other stack guides follow the root `<STACK>.md` pattern. The T3 iOS coverage question waits for the toolchain baseline.
- [Which crypto options fit each security profile?](issues/03-crypto-options.md): no secured profile can rely on platform crypto alone at Android 26 / iOS 15. Classical fits standard Noise (XX, then IK/KK; every message <100 B). Post-quantum means hybrid X25519 + ML-KEM-768 with classical authentication, via Noise hfs (~2.4 KB) or a cairn-style pairing plus a 106 B resume, and always needs fragmentation. Candidate providers: libsodium, Rust aws-lc-rs, or a platform split.
- [Which routing and topology strategies suit phone BLE meshes?](issues/04-routing-topology-strategies.md): with only 3–6 links per phone, neighbor selection matters as much as routing, and mesh-wide flooding won't scale to ~1,000 nodes without a hop limit. The simulation shortlist covers neighbor selection, unicast, broadcast and store-and-forward strategies.

## Not yet specified

- Wire format and codec (framing, fragmentation, versioning, stable IDs) — hangs on bearer, security and routing.
- Delivery/read receipt semantics and message lifecycle states.
- Store-and-forward design (quota, TTL, custody, privacy of held messages).
- Message size cap.
- Power policy across presets and density.
- Diagnostics and troubleshooting surface (stable diagnostic codes, field tooling).
- Testing architecture (virtual multi-node transport, Robolectric, device-lab automation, fuzzing).
- Protocol spec format and conformance vectors.
- Release, versioning and API-compatibility process.
- CI integration with the device lab.
- Neighbor-list privacy: whether link-state and route messages are hidden from passive listeners per security profile.
- Node ID and duplicate-detection key sizes (drive every overhead estimate).
- Compression: none vs bounded (Bridgefy gzip-bomb and side-channel lessons).
- Energy measurements (cost per KB and per connection-hour on lab phones) to back battery claims.
- Shared cross-platform limits table, enforced by conformance tests.
- Optional non-BLE bearers (Wi-Fi Aware, Multipeer Connectivity, Nearby Connections) for throughput, with BLE as baseline; may belong in Out of scope.

## Out of scope

- Encrypted named groups (group key agreement, e.g. MLS).
- File transfer and streaming.
- Targets other than Android and iOS.
- Wire compatibility with Bluetooth SIG Mesh, bitchat, Briar or other meshes.
- Multi-device users and account sync.
