# How are modules cut, and how do feature toggles and presets work?

Type: grilling
Status: open
Blocked by: 09, 12, 14

## Question

Decide artifacts (core, bearers, routing, store-and-forward, security profiles), which features are runtime configuration vs separate modules, how presets map to modules and settings, and the dependency graph.

- From the [crypto provider decision](10-rust-approval.md): decide whether MeshLink's Kotlin Noise implementation (XX/XXpsk3/IK/hfs) ships as a standalone artifact, and where `CryptoProvider` and the libsodium binding live; `unsecured` builds should not need native crypto if feasible.

## Scope additions (from decisions)

- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): iOS code must be thin adapters (Constitution 4.0.0 T3), so module cuts keep logic in common code. The libsodium build (NDK 30, cached XCFramework) is one native unit; decide which module owns it.
- From the [documentation architecture](16-documentation-architecture.md) (ADR 0006): plan a build-logic home for the docs generators and checks (`checkDependencyRegister`, region sync, reference generation); nested `AGENTS.md` files arrive with module code.
- From the [public API shape](15-public-api-shape.md) (ADR 0007): decide what each built-in preset contains (it must be printable via `describe()` and generate a reference table), where the Swift overlay target and the `meshlink-testing` fixtures artifact live, and how optional features appear in the single `MeshLinkConfig` DSL.
