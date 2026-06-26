# MeshLink glossary and acronym reference

This page defines recurring MeshLink project terms and acronyms.

Use it when a document mentions a build, interoperability, trust, transport,
or reference-app term that is obvious to maintainers but not yet obvious to
you.

## Scope

**In scope:** recurring MeshLink project terms used across the docs set,
including official reference-app surface and export vocabulary.

**Out of scope:** full API behavior, task steps, and architectural rationale.

## Quick lookup

| Term | Meaning |
|---|---|
| `Advanced controls` | the supported reference-app surface for deeper runtime inspection and deliberate operator actions |
| `AGP 9` | Android Gradle Plugin 9, the Android build-tooling generation used in this repository's migration and contributor docs |
| `BCV` | Binary Compatibility Validator, the compatibility check and checked-in API dump used to track public SDK surface changes |
| `Dokka` | generated Kotlin API reference tooling used for contributor-facing API browsing |
| `Full-payload export` | an operator opt-in reference-app export that includes payload content and is available only from a supported live session before it ends |
| `GATT` | Generic Attribute Profile, the BLE attribute-oriented communication model |
| `GATT-notify side bearer` | the iPhone-hosted GATT notification path used on some mixed-platform large-transfer paths |
| `Guided first exchange` | the supported first-proof reference-app surface |
| `Lab` | the explicitly non-normative reference-app surface for proof-only and benchmark-only behavior |
| `L2CAP` | Logical Link Control and Adaptation Protocol, the channel-oriented BLE bearer MeshLink prefers as its direct transport posture |
| `Proof app` | the Android or iOS host app used for physical proof and benchmark work outside the reference-app product-like walkthrough |
| `Proof fixture` | a proof app used as a retained transport-validation or benchmark surface rather than as the supported product-like evaluation surface |
| `Proof peer` | a device running a proof app and participating in an exchange or benchmark run |
| `Recent history` | the reference-app surface that lists retained sessions |
| `Redacted export` | the default reference-app export that omits full payload content |
| `Retained session` | a previously captured reference-app session reopened from Recent history |
| `SKIE` | the Swift interop tooling used on MeshLink's generated Apple frameworks to improve Swift naming and concurrency surfaces |
| `Solo exploration` | the one-device, non-authoritative reference-app surface |
| `Supported live session` | the current authoritative reference-app session running on Guided first exchange or Advanced controls |
| `Technical timeline` | the reference-app evidence surface for live or retained lifecycle, peer, diagnostic, and message events |
| `TOFU` | trust on first use, the local trust model MeshLink uses to pin peer identity continuity |

## Details

### `Advanced controls`

**Category:** reference-app surface term

**Description:** The supported reference-app surface for deeper runtime
inspection, send controls, lifecycle actions, and trust-reset actions.

**Notes:** Shares the same supported live session as `Guided first exchange`.

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

**Notes:** Dokka output is supplemental to the human-written Diataxis docs.

### `Full-payload export`

**Category:** reference-app export term

**Description:** An operator opt-in export that includes payload content in the
session artifact.

**Notes:** Available only from a supported live session before that session
ends.

### `GATT`

**Category:** BLE transport term

**Description:** Generic Attribute Profile. In MeshLink docs, GATT refers to
the BLE attribute-oriented transport model used for reads, writes, and
notifications.

**Notes:** Contrasted most often with MeshLink's L2CAP-preferred transport posture.

### `GATT-notify side bearer`

**Category:** MeshLink transport term

**Description:** The iPhone-hosted GATT notification path used on some mixed
Android/iOS large-transfer paths.

**Notes:** This is a transport adaptation inside MeshLink, not a separate
application mode the host app has to select.

### `Guided first exchange`

**Category:** reference-app surface term

**Description:** The supported reference-app surface used for the fastest
end-to-end first proof on two devices.

**Notes:** Shares the same supported live session as `Advanced controls`.

### `Lab`

**Category:** reference-app surface term

**Description:** The explicitly non-normative reference-app surface reserved
for proof-only and benchmark-only behavior.

**Notes:** `Lab` is kept separate from the supported product-evaluation path.

### `L2CAP`

**Category:** BLE transport term

**Description:** Logical Link Control and Adaptation Protocol. In MeshLink
docs, L2CAP refers to the direct, channel-oriented BLE bearer MeshLink prefers
for its transport posture.

**Notes:** Often discussed alongside route establishment, large transfers, and
mixed-platform bearer tradeoffs.

### `Proof app`

**Category:** validation-surface term

**Description:** The Android or iOS host app used for physical proof and
benchmark work outside the reference-app product-like walkthrough.

**Notes:** A `Proof app` can act as a `Proof peer` in a run or as a `Proof
fixture` when the emphasis is on retained transport-validation behavior.

### `Proof fixture`

**Category:** validation-surface term

**Description:** A proof app used as a retained transport-validation or
benchmark surface rather than as the supported product-like evaluation surface.

**Notes:** MeshLink docs use `Proof fixture` when the role of the host matters
more than the peer-to-peer interaction itself.

### `Proof peer`

**Category:** validation-surface term

**Description:** A device running a proof app and participating in an exchange
or benchmark run.

**Notes:** When the receiver role matters, the docs say `receiving proof peer`.

### `Recent history`

**Category:** reference-app evidence term

**Description:** The surface that lists retained sessions after they leave the
current live session path.

**Notes:** Reopened sessions from `Recent history` stay on the redacted export
path.

### `Redacted export`

**Category:** reference-app export term

**Description:** The default reference-app export that includes metadata and
redacted previews without full payload content.

**Notes:** This is the only export mode available for retained sessions, `Solo
exploration`, and `Lab` sessions.

### `Retained session`

**Category:** reference-app evidence term

**Description:** A previously captured reference-app session reopened from
`Recent history`.

**Notes:** A `Retained session` is separate from the current live session and
stays on the redacted export path.

### `SKIE`

**Category:** Swift interoperability term

**Description:** The Swift interop tooling used on MeshLink's generated Apple
frameworks. In practice, it is what makes the Swift-facing API feel more native
by improving naming and surfacing suspend functions and flows more naturally.

**Notes:** Most relevant in the Swift how-to and Dokka/SKIE tooling posture
notes.

### `Solo exploration`

**Category:** reference-app surface term

**Description:** The one-device, non-authoritative reference-app surface used
for orientation and walkthroughs when a second device is not available.

**Notes:** `Solo exploration` does not count as live peer proof.

### `Supported live session`

**Category:** reference-app session term

**Description:** The current authoritative reference-app session running on
`Guided first exchange` or `Advanced controls`.

**Notes:** Only a `Supported live session` can produce a `Full-payload export`
before it ends.

### `Technical timeline`

**Category:** reference-app evidence term

**Description:** The evidence surface that shows live or retained lifecycle,
peer, diagnostic, and message events and also exposes session-end and export
actions.

**Notes:** This is an evidence surface, not a separate runtime mode.

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
- [MeshLink reference app overview](../../meshlink-reference/README.md)
- [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md)
- [The trust model](../explanation/trust-model.md)
- [About the L2CAP-preferred transport posture](../explanation/why-l2cap-first.md)
- [How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md)
