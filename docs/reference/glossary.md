# MeshLink glossary and acronym reference

This page defines recurring MeshLink project terms and acronyms.

Use it when a document mentions a build, interoperability, trust, or transport
term that is obvious to maintainers but not yet obvious to you.

## Scope

**In scope:** recurring MeshLink project terms used across the docs set.

**Out of scope:** full API behavior, task steps, and architectural rationale.

## Quick lookup

| Term | Meaning |
|---|---|
| `AGP 9` | Android Gradle Plugin 9, the Android build-tooling generation used in this repository's migration and contributor docs |
| `BCV` | Binary Compatibility Validator, the compatibility check and checked-in API dump used to track public SDK surface changes |
| `Dokka` | generated Kotlin API reference tooling used for contributor-facing API browsing |
| `GATT` | Generic Attribute Profile, the BLE attribute-oriented communication model |
| `GATT-notify side bearer` | the iPhone-hosted GATT notification path used on some mixed-platform large-transfer paths |
| `L2CAP` | Logical Link Control and Adaptation Protocol, the channel-oriented BLE bearer MeshLink prefers as its direct transport posture |
| `SKIE` | the Swift interop tooling used on MeshLink's generated Apple frameworks to improve Swift naming and concurrency surfaces |
| `TOFU` | trust on first use, the local trust model MeshLink uses to pin peer identity continuity |

## Details

### `AGP 9`

**Category:** build tooling term

**Description:** Android Gradle Plugin 9. In MeshLink docs, this usually refers
to the current Android build-tooling generation and the module-shape migration
that moved the repository onto that tooling line.

**Notes:** Most relevant in contributor and tooling docs.

### `BCV`

**Category:** API compatibility term

**Description:** Binary Compatibility Validator. In MeshLink, BCV refers to the
public API dump and compatibility checks that help track whether the SDK's
public surface changed.

**Notes:** Most relevant in API, contributor, and release-governance docs.

### `Dokka`

**Category:** documentation tooling term

**Description:** Generated Kotlin API reference tooling used to render
contributor-facing API browsing output for the SDK and shared reference-app
module.

**Notes:** Dokka output is supplemental to the human-written Diátaxis docs.

### `GATT`

**Category:** BLE transport term

**Description:** Generic Attribute Profile. In MeshLink docs, GATT refers to
the BLE attribute-oriented transport model used for reads, writes, and
notifications.

**Notes:** Contrasted most often with MeshLink's L2CAP-first transport posture.

### `GATT-notify side bearer`

**Category:** MeshLink transport term

**Description:** The iPhone-hosted GATT notification path used on some mixed
Android/iOS large-transfer paths.

**Notes:** This is a transport adaptation inside MeshLink, not a separate
application mode the host app has to select.

### `L2CAP`

**Category:** BLE transport term

**Description:** Logical Link Control and Adaptation Protocol. In MeshLink
docs, L2CAP refers to the direct, channel-oriented BLE bearer MeshLink prefers
for its transport posture.

**Notes:** Often discussed alongside route establishment, large transfers, and
mixed-platform bearer tradeoffs.

### `SKIE`

**Category:** Swift interoperability term

**Description:** The Swift interop tooling used on MeshLink's generated Apple
frameworks. In practice, it is what makes the Swift-facing API feel more native
by improving naming and surfacing suspend functions and flows more naturally.

**Notes:** Most relevant in the Swift how-to and Dokka/SKIE tooling posture
notes.

### `TOFU`

**Category:** trust-model term

**Description:** Trust on first use. MeshLink uses TOFU to pin a peer's
identity material on first verified contact and then check continuity on later
contacts.

**Notes:** TOFU provides local continuity, not proof of a peer's real-world
identity.

## Related references

- [MeshLink SDK API reference](meshlink-sdk-api.md)
- [MeshLink runtime behavior reference](meshlink-runtime-behavior.md)
- [Contributor build, test, and verification reference](contributor-reference.md)
- [The trust model](../explanation/trust-model.md)
- [About the L2CAP-first transport posture](../explanation/why-l2cap-first.md)
- [How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md)
