# Is the October toolchain still the newest stable, and can Xcode 27.2 replace Xcode 16.4?

Type: research
Status: resolved

## Question

Recheck every row of ADR 0005's version tuple against primary sources as of now: Kotlin, Gradle, AGP, SKIE, kotlinx-coroutines, Kover, Spotless/ktlint, detekt (is 2.0 stable?), Dokka, vanniktech maven-publish, Robolectric, NDK, libsodium, and KGP's built-in ABI validation (still experimental?). Establish whether Xcode 27.2 works with the newest Kotlin, builds for iOS 15.0, and can install and debug on the lab's iOS 15 and 16 phones; what GitHub's macOS runner images ship; and which tool can enforce the Gradle module dependency direction of ADR 0011 (candidates, license, maintenance, E3 fit).

## Context

- ADR 0005 (decided 2026-10-31) calls Xcode 16.4 a hard constraint; Xcode 27.2 is now installed on this Mac.
- ADR 0004 rejected CryptoKit post-quantum partly because Xcode 16.4 lacked the iOS 26 SDK; the iOS 15 deployment target still rules it out at runtime.
- ADR 0011 decision 2: the dependency-direction tool is chosen with the first module and needs an E3 row.

## Answer

2026-11-13, research on [`research/toolchain-refresh`](https://github.com/trancee/MeshLink/blob/research/toolchain-refresh/docs/research/2026-11-13-toolchain-refresh.md) (commit `6a4f010`).

- **Newer stable releases than ADR 0005 names:** Dokka 2.3.0 (fixes CVEs in its bundled libraries, one critical), Spotless 8.11.0, Robolectric 4.18 and Rust 1.100.0. Gradle 9.9.1 and AGP 9.5.1 are released but aren't in KGP 2.4.21's compatibility table. Kotlin 2.4.21 is still the newest stable (2.5.0 expected in December 2026; SKIE doesn't support 2.5 yet).
- **Still not stable:** detekt 2.0 (alpha.6), Kover (Beta) and KGP's ABI validation (Experimental).
- **Xcode:** Kotlin 2.4.21 lists Xcode 26.4; newer Xcode versions get a warning, not an error. Xcode 27.2 builds for iOS 15 and later, but debugs on devices only from iOS 17. No Xcode both runs on macOS 27 and debugs iOS 15/16 devices.
- **Runners:** `macos-15` (Xcode 16.4 default, 26.0–26.3 also installed), `macos-26` (26.6) and the preview `xcode-27` image (27.0–27.2). NDK 30 has to be installed in CI.
- **Dependency direction:** a roughly 50-line check in `build-logic/` with no new dependency. The fallback, modules-graph-assert, only partly supports KMP.
- **Open, now [ticket 10](10-xcode27-smoke.md):** does Kotlin 2.4.21 build with Xcode 27.2, and does that build install and run on iOS 15/16 phones?
