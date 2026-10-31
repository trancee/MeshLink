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
- **Governance:** read `CONSTITUTION.md`, `AGENTS.md`, `GLOSSARY.md` first. Every dependency needs an E3 rationale and user approval; Rust only via the Rust-approval ticket. Verify claims against primary sources; never assume — ask with options + recommendation.
- **Evidence bar:** radio decisions need real-device evidence across Android↔Android, Android↔iOS, iOS↔iOS. Device lab: 29 Android phones (`../android-devices/devices.md`); iPhones on this Mac (`xcrun devicectl list devices`).
- **Prior art (study only, never copy code):** `../MeshLink-template` (SPEC.md, `docs/explanation/template-design-review.md` = its self-critique), `../cairn` (`docs/agents/lessons.md`, ADRs 0001/0003/0025/0038–0042, `docs/research/`).
- **Skills to consult:** grilling + domain-modeling (always for HITL); research; prototype; ble-protocol-stack, ble-throughput, android-ble*, corebluetooth, noise-protocol, kotlin-multiplatform, kotlin-gradle, skie, ubique-uniffi, robolectric, diataxis, wycheproof as relevant.
- **Research findings** live on local `research/<slug>` branches (worktrees, committed, not pushed); tickets link the branch + file.

## Decisions so far

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

## Out of scope

- Encrypted named groups (group key agreement, e.g. MLS).
- File transfer and streaming.
- Targets other than Android and iOS.
- Wire compatibility with Bluetooth SIG Mesh, bitchat, Briar or other meshes.
- Multi-device users and account sync.
