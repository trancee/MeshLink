# In what order is 0.1.0 built, and how is it sliced?

Type: grilling
Status: resolved
Blocked by: 03, 05

## Question

Decide the first end-to-end slice (the tracer bullet), the milestones after it, the slice size and the ordering rule across codec, crypto binding, Noise, sans-IO core and VirtualAir, routing, platform adapters, public API, Swift overlay, docs generators and release.

## Context

- ADR 0017 (sans-IO core, VirtualAir), ADR 0011 (modules), ADR 0007 (API).

## Answer

Resolved 2026-11-13 (grilling; every recommendation accepted). No ADR: the order is cheap to change, so it lives here and in the backlog's `spec.md`.

1. **Slice size:** one ticket = one PR = one agent session. It delivers one observable behavior (through the public API or a spec section) or one piece of infrastructure, and ends green on every required gate (Kover 100%, ABI dump, docs). No layer is built ahead of the slice that needs it: a frame codec and its spec vectors land with the first slice that sends that frame.
2. **M0 Scaffold (no behavior):** the Gradle build (4 modules, `build-logic`, the dependency-direction check, version catalog); CI jobs made required checks on `main` (ADR 0021 decision 6); Kover, detekt, Spotless, ABI validation and Dokka; Robolectric 4.18 proven on SDK 26 and 37; `publishToMavenLocal`; the XCFramework consumed by a SwiftPM `binaryTarget` test. libsodium waits for M3.
3. **M1 Tracer bullet:** two `SecurityProfile.Unsecured` nodes on `VirtualAir` discover each other and exchange one direct message through the public API (`start`, `send`, `receive`, delivery status), in `commonTest` on the JVM and the iOS simulator.
4. **M2 Radio tracer:** the same scenario on real phones over GATT only: the Android adapter (Robolectric), the iOS adapter behind its port, a minimal `labapp/` and `tools/lab/`; proof on Android↔Android, Android↔iOS and iOS↔iOS lab pairs.
5. **Milestones after that, in order:**
   - M3 Security: the libsodium binding and host build, the vector runner, the identity certificate, Noise `XX`/`XXhfs`, the `Classical` and `PostQuantum` profiles.
   - M4 Links: advertisement layout E and rotation, the L2CAP CoC upgrade with GATT fallback, PHY, link limits, flow control, fragments, rekey.
   - M5 Routing: topology, density, routed unicast and route discovery, flood and Plumtree broadcast, metadata exposure.
   - M6 End-to-end: `IK`/`IKhfs` sessions, the HPKE X-Wing seal, delivery and read receipts, certificate lookup.
   - M7 Store-and-forward, size classes and large messages, tombstones.
   - M8 Presets and toggles, the diagnostics catalog, the Swift overlay, the agent docs generators.
   - M9 Release: the release workflow (dry run first), Pages, `meshlink-swift`, the lab-fault ticket, the 8-phone evidence run and the 15-phone fleet smoke run (ADR 0017 v2), then 0.1.0.
6. **Ordering:** one ordered list with explicit `Blocked by:` lines. Independent lanes may run in parallel (for example M3's binding beside M2's iOS adapter); the owner merges one at a time. Each radio slice names its minimal device pair under "Devices needed"; the full 8-phone evidence runs only at release.
7. **Detail:** ticket 09 writes every M0–M9 ticket in full (acceptance criteria and proof commands). A claimed ticket found bigger than one session goes back to `needs-triage` to be split; it never grows mid-PR.
8. **Milestones:** the backlog's `spec.md` lists M0–M9 with exit criteria and their tickets in order. Ticket numbers follow build order. The ADR 0021 ticket template is unchanged.

**Correction (2026-11-13, found while writing the backlog in ticket 09; user approved):** the M1 tracer already needs libsodium. Every node has an Ed25519 identity whose NodeId is a BLAKE2b hash (identity spec), mesh tags use libsodium BLAKE2b in every profile (ADR 0011 decision 7), and ADR 0017 decision 4 forbids fake crypto in host tests. A minimal binding therefore moves into M1, ahead of the tracer: the verified native builds, the JNI and cinterop shim with the AES symbol check, and a `CryptoProvider` limited to BLAKE2b, Ed25519 key generation and secure random, with the `node-id` and mesh-tag derivation vectors. M3 extends that binding with the remaining primitives, then adds the vector runner and Noise.
