# How to structure a robust MeshLink integration

This guide shows you how to shape a MeshLink integration that stays predictable
once it moves beyond a proof of concept.

Use it when you want to:

- move from a first working demo to an app-owned integration
- choose where MeshLink should live in your app architecture
- add the guardrails that make delivery, trust, and diagnostics easier to
  reason about

If you still need the basic runtime bootstrap steps, use
[How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md).
If you want the design rationale behind these choices, read
[About integrating MeshLink well](../explanation/about-integrating-meshlink.md).

## 1. Put MeshLink behind one long-lived app owner

Create one app-owned MeshLink runtime for the mesh domain you are operating in.

Good owners include:

- an application service
- a controller
- a long-lived view model
- a shared app runtime object

Keep that runtime alive across many send and receive operations.

Do **not** create a fresh runtime per screen visit or per send. That throws away
peer visibility, trust continuity, retry state, and diagnostics.

## 2. Choose `appId` deliberately

Treat `appId` as an environment boundary, not as display text.

A good default pattern is:

- one production `appId` for production peers
- one staging `appId` for staging peers
- one isolated `appId` for proof, lab, or test work

If devices unexpectedly discover each other, or unexpectedly fail to discover
one another, check `appId` first.

## 3. Clear platform readiness before you call `start()`

Before you start MeshLink, make sure the platform-specific prerequisites are
already resolved.

- On Android, request the required Bluetooth and Nearby/Location permissions
  first.
- On iOS, install the required crypto bridge during app startup and clear the
  Bluetooth prompt before debugging discovery.

Treat permissions and bridge installation as startup prerequisites, not as
something to debug after routing or trust.

If you are blocked here, use [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

## 4. Collect all four public streams and keep them separate

Map each public stream to one clear responsibility:

- `state` → runtime lifecycle state
- `peerEvents` → peer presence and connectivity
- `diagnosticEvents` → logs, operator tooling, troubleshooting
- `messages` → inbound application payloads

Do not try to infer mesh health from `messages` alone. When discovery or
delivery looks wrong, `diagnosticEvents` is the first place to look.

## 5. Treat `PeerId` as opaque

Use `PeerId` as a stable handle in your app state, but do not parse business
meaning out of it.

A good pattern is:

- keep the `PeerId` as the routing handle
- store your own human-friendly label next to it
- log the redacted `PeerId` string form when you need support visibility

## 6. Treat `SendResult.Sent` as transport success only

`SendResult.Sent` means MeshLink completed its delivery path.

It does **not** mean that the remote app:

- persisted the payload
- showed it to a user
- completed a business action
- produced a user-visible acknowledgement

If your product needs stronger guarantees, add an application-level receipt or
response message on top of MeshLink.

## 7. Make trust reset explicit

Only call `forgetPeer()` from a deliberate user or operator action.

When you do, decide what should also happen in your app:

- cached peer metadata
- conversation history labels
- trust-related UI state
- support logs or audit notes

Do not silently forget peers as part of ordinary retry logic.

## 8. Choose a power strategy on purpose

If you use `PowerMode.Automatic`, feed real battery updates into
`updateBattery()`.

If your app will not own battery observation, prefer an explicit fixed mode and
make that choice visible in your integration code.

For the underlying model, read [Power management](../explanation/power-management.md).

## 9. Give diagnostics a home

At minimum, keep MeshLink diagnostics visible in development builds.

A stronger production-shaped pattern is to expose diagnostics in:

- a hidden support screen
- an operator log surface
- structured app logging
- issue-reproduction notes

Keep diagnostics separate from payload archives. Diagnostics should explain what
MeshLink is doing without turning logs into message storage.

## 10. Verify with the right harness

Use the right validation path for the question you are answering:

- first success path → [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md)
- host-app bootstrap → [How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md)
- guided evaluation and logs → [How to use the MeshLink reference app](../../meshlink-reference/README.md)
- real-device physical validation → the proof apps and retained benchmark evidence

## Quick review checklist

Before you call the integration done, check that you have:

- one long-lived runtime owner
- one deliberate `appId` per environment
- platform readiness cleared before `start()`
- all four public streams wired to distinct responsibilities
- application-level receipts if your product needs stronger guarantees
- explicit trust reset UX
- a visible diagnostics surface
- an intentional power-policy choice

## Related docs

- [How to add MeshLink to your app](add-meshlink-to-your-app.md)
- [How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md)
- [How to use MeshLink from Swift](use-meshlink-from-swift.md)
- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
- [The trust model](../explanation/trust-model.md)
- [Power management](../explanation/power-management.md)
