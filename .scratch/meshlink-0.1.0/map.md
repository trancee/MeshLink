# Map: MeshLink 0.1.0

Label: `wayfinder:map`
Status: closed (2026-11-13), destination reached

## Destination

An ordered 0.1.0 implementation backlog: vertical slices an agent can take one at a time, each with acceptance criteria and proof commands, covering the whole v1 spec (ADRs 0001–0020, protocol 1), the release infrastructure and workflow of ADR 0019, and the device evidence record 0.1.0 needs. Every decision the build depends on is locked first; the code is written outside this map, ticket by ticket.

## Notes

- **Starting point:** the closed [v1 map](../meshlink-v1/map.md) and its handoff. The spec bundle is authoritative: `PROJECT.md`, `GLOSSARY.md`, `docs/adr/`, `docs/spec/` (protocol 1, 253 vectors), `docs/reference/dependencies.md`.
- **Scope:** 0.1.0 ships the whole v1 spec: all three security profiles, direct and routed unicast, broadcast, receipts, store-and-forward, large messages, certificate lookup, diagnostics and presets. No feature is cut, because the simulations behind the spec assumed the whole system.
- **Governance:** read `CONSTITUTION.md`, `AGENTS.md`, `GLOSSARY.md` and `KOTLIN.md` first. Every new dependency needs an E3 rationale and user approval. Verify claims against primary sources; ask with options and a recommendation.
- **Skills to consult:** grilling and domain-modeling (every HITL ticket); research; kotlin-multiplatform, kotlin-gradle, skie, detekt, kover, dokka, kotlin-binary-compatibility, maven-central-publishing, robolectric, tdd, writing-for-agents as relevant.
- **Research:** findings land on a `research/<slug>` branch, pushed, with no PR and never merged; the ticket links the branch.
- **Device lab:** `../android-devices/devices.md` (Android and iPhones); read `docs/agents/lessons.md` before any radio, device or gate work.

## Decisions so far

- [Is the October toolchain still the newest stable, and can Xcode 27.2 replace Xcode 16.4?](issues/01-toolchain-refresh.md): Kotlin 2.4.21 is still current. Dokka 2.3.0 fixes CVEs; detekt 2, Kover and ABI validation are still not stable. Xcode 27.2 builds for iOS 15 but debugs only on iOS 17+ devices. A build-logic check needs no new dependency. Whether Kotlin builds with Xcode 27.2 is now ticket 10.
- [Which prose docs site generator fits 0.1.0?](issues/02-docs-site-generator.md): no preferred generator is stable. Zensical is in beta (stable expected around February 2027) and builds the docs unchanged; Starlight needs every page edited. The choice is between Dokka only, Zensical's beta, Starlight and mdBook.
- [What are MeshLink's Maven coordinates, Kotlin package and Swift module names?](issues/04-coordinates-and-names.md): groupId `ch.trancee.meshlink` (domain namespace `ch.trancee`), packages under `ch.trancee.meshlink`, XCFramework `MeshLinkKit`, Swift package `MeshLink` in `trancee/meshlink-swift`. Recorded in ADR 0011 v8.
- [How does an implementation ticket get from the backlog to main?](issues/05-backlog-to-main.md): local backlog in `.scratch/meshlink-0.1.0-build/` with a fixed template; every change through a squash-merged PR behind three required checks; agents may open PRs on `ready-for-agent` tickets, merges need approval. Recorded in ADR 0021.
- [What device evidence does the 0.1.0 release need?](issues/06-release-device-evidence.md): 8 phones (Android 8–17, iOS 15.8–27), pairwise and 3-hop mesh scenarios plus a 15-phone fleet smoke run, exactly-once delivery as the pass bar, an in-repo `labapp/` driven by `tools/lab/`. Recorded in ADR 0017 v2.
- [Does 0.1.0 publish a prose docs site, and with which generator?](issues/08-docs-site.md): no; Pages carries only Dokka at `/api/` with a small root page, prose stays on GitHub; re-evaluate at Zensical's first stable release or 1.0.0. Recorded in ADR 0006 v3.
- [Does Kotlin 2.4.21 build with Xcode 27.2, and do its builds run on iOS 15 and 16 phones?](issues/10-xcode27-smoke.md): yes; the framework and app build without a Kotlin Xcode warning, and the app runs on iOS 15.8.8 through 27.2 with every vector matching, using libimobiledevice on iOS 15/16 and devicectl from iOS 17.
- [Which toolchain and build checks does the first build start on?](issues/03-toolchain-decision.md): hold Kotlin 2.4.21 / Gradle 9.7.1 / AGP 9.3.3; Dokka 2.3.0, Spotless 8.11.0, Robolectric 4.18 (proven by the scaffold), Rust 1.100.0; Xcode 27.2 locally, Xcode 26.4.1 on `macos-26` in CI with a scheduled `xcode-27` job; a dependency-free `build-logic/` direction check. Recorded in ADR 0005 v4, 0011 v9 and 0019 v2.
- [In what order is 0.1.0 built, and how is it sliced?](issues/07-build-order-and-slices.md): one behavior per PR, no layer ahead of need; M0 scaffold, M1 `Unsecured` two-node tracer on `VirtualAir`, M2 the same over GATT on real phones, then security, links, routing, end-to-end, store-and-forward and large messages, presets/diagnostics/Swift overlay, release. Every ticket written in full; milestones in the backlog's `spec.md`.
- [Write the 0.1.0 backlog](issues/09-write-backlog.md): 64 tickets in M0–M9, all ready; libsodium in M1, signing folded into broadcast, the Unlicense, Keystore/Keychain identity storage, a GATT `CREDIT` frame, extended advertising and the Live Activity hook deferred.

## Not yet specified

None. The last open patches graduated into backlog tickets: Jazzer on host tests in [Fuzz every decoder on a schedule with Jazzer](../meshlink-0.1.0-build/issues/34-jazzer-fuzzing.md), the Swift overlay in [Use MeshLink naturally from Swift](../meshlink-0.1.0-build/issues/55-swift-overlay.md), and libsodium's size for unsecured-only apps in [Build libsodium from verified source for the host, Android and iOS](../meshlink-0.1.0-build/issues/06-libsodium-native-build.md).

## Out of scope

- Re-measuring the energy model on a Pixel 7 or newer ([v1 ticket 34](../meshlink-v1/issues/34-energy-second-android.md)): 0.1.0 does not depend on it.
- Watching libp2p for post-quantum Noise (`XXhfs`) vectors (ADR 0002 amendment): an ongoing watch, not a 0.1.0 step.
- Offering MeshLink's HPKE work to libsodium (ADR 0004): optional outreach after the build.
- Signed prekeys for store-and-forward forward secrecy: deferred by ADR 0001.
- A separate artifact without crypto for the unsecured profile: deferred by ADR 0011.
- Extended advertising and the iOS Live Activity hook: deferred by the ADR 0010 and ADR 0011 amendments of 2026-11-13.
