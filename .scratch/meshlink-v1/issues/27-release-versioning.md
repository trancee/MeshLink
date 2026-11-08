# What is the release, versioning and API-compatibility process?

Type: grilling
Status: resolved

## Question

Decide versioning (library and protocol), API compatibility checks, the release workflow for Maven Central and the XCFramework ZIP, the `meshlink-swift` update and smoke test (ADR 0011), and changelog rules.

## Context

- ADR 0005: ABI validation was not stable at baseline time; recheck.

## Answer

ADR 0019.

- **Versions:** one SemVer version for every artifact, starting at 0.1.0. The protocol version byte is independent and changes only on wire breaks. A new protocol major also speaks the previous one for at least one MINOR.
- **API checks:** KGP ABI dumps (JVM and klib) with `explicitApi()` strict, and `swift-api-digester` for the Swift overlay. A dump change needs a changelog entry.
- **Deprecation:** WARNING, then ERROR, then removal, at least one MINOR apart.
- **Changelog:** hand-written `CHANGELOG.md`, enforced by `checkChangelog`.
- **Release workflow**, started by a tag:
  1. gates and the device evidence check;
  2. manual approval;
  3. signed Maven Central upload with automatic release;
  4. the attested XCFramework ZIP and a GitHub Release;
  5. Dokka on Pages;
  6. the `meshlink-swift` update through a GitHub App token;
  7. Gradle and SwiftPM consumer smoke tests.
- **Support:** only the latest release is supported before 1.0.

Amended: ADR 0005 v3, ADR 0011 v6, ADR 0012 v6. Ticket 30 is unblocked.
