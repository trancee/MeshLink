# What is the testing architecture?

Type: grilling
Status: resolved

## Question

Decide the virtual multi-node transport, Robolectric use, device-lab automation and its CI integration, fuzzing (decoders per ADR 0012), and the conformance vector runner and coverage (ADR 0006).

## Context

- Merges fog items: testing architecture, conformance vector runner, CI with the device lab.
- ADR 0011: `meshlink-testing` (Kotlin) and `MeshLinkTesting` (Swift) run the same reference scenarios.

## Answer

ADR 0017.

- **Core and multi-node tests:** a sans-IO core with an injected clock, random source and radio port. Multi-node tests run in `commonTest` under virtual time.
- **One virtual radio:** `VirtualAir` in `meshlink-testing` is the only fake radio. It's seeded and models MTU, latency, loss, link caps, scan windows and disconnects.
- **Real crypto on the host:** a test-only host build of libsodium and the JNI shim.
- **Robolectric:** tests only the Android adapter, on SDK 26 and 37, with our own shadows where Robolectric falls short.
- **iOS:** `commonTest` and `iosTest` run on the simulator. The CoreBluetooth calls are thin and exercised on devices.
- **Device lab:** `tools/lab/` host scripts, run by hand before releases. Each run writes a T9 evidence record covering all platform pairs, both roles, and the oldest and newest OS. There's no self-hosted runner.
- **Fuzzing:** a deterministic mutation test for every decoder on every pull request, plus Jazzer 0.30.0 (standalone driver) on a schedule, with a committed corpus.
- **Vectors:** one JSON vector runner in `commonTest`, whose files the Rust reference also reads.
- **Density meshes:** 2–12 nodes on pull requests, 50–200 nodes on a schedule.
- **CI and flakiness:** pull requests target at most 15 min. No automatic retries; a flaky test is a bug.

ADR 0005 is amended to v2, ADR 0011 to v5, and the dependency register gains two rows.
