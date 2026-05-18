# About integrating MeshLink well

This page is about the shape of a good MeshLink integration.

It does not tell you the step-by-step mechanics of setup. For that, use [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md). For the exact public surface, use the [MeshLink SDK API reference](../reference/meshlink-sdk-api.md).

## MeshLink is an app service, not a utility call

MeshLink works best when you treat it as a long-lived app-owned runtime.

That means:

- create it once for the app service, controller, or view model that owns mesh behavior
- keep it alive across many send and receive operations
- bind its public streams into your app state instead of polling for status

A poor integration pattern is creating a fresh runtime per send. That throws away discovery, trust continuity, diagnostics, retry state, and peer visibility.

## `appId` is part of your architecture

`appId` is not cosmetic. It defines the logical mesh domain.

Developers should treat it the same way they treat an environment boundary:

- production peers share one production `appId`
- staging peers use a different staging `appId`
- local proof and benchmark work often uses an isolated transient `appId`

If devices are unexpectedly discovering each other, the first thing to inspect is whether they are still sharing an `appId`.

## The public streams are the integration surface

MeshLink exposes four distinct signals because they solve different app problems:

- `state` tells you what the runtime is doing
- `peerEvents` tells you what nearby peers are doing
- `diagnosticEvents` tells you what the mesh machinery is doing
- `messages` tells you what user payloads arrived

Good integrations keep those concerns separate.

A common mistake is using `messages` as the only source of truth and ignoring diagnostics. That works until you need to explain why delivery is retrying, why a peer disappeared, or why a trust mismatch blocked traffic.

## `SendResult.Sent` is transport success, not a user read receipt

MeshLink's public `send()` contract describes whether the SDK successfully delivered the payload through the mesh transport path.

That does **not** automatically mean:

- the receiving app surfaced the content to a human
- the remote app persisted it
- the user read it
- the business operation tied to that message completed

If your product needs application-level acknowledgement, add an application-level receipt or response message on top of MeshLink rather than overloading `SendResult`.

## Trust reset is a product decision

MeshLink uses local continuity, not central account identity.

That means trust reset needs an explicit app UX and policy:

- when should the user be allowed to forget a peer?
- what should happen to cached conversation or device metadata afterward?
- how should a trust failure be explained?

A good integration does not silently call `forgetPeer()` behind the user's back. That turns an identity continuity event into hidden mutable state.

For the underlying model, read [The trust model](trust-model.md).

## The peer lifecycle is intentionally conservative

Peers in a BLE mesh are noisy. They appear, disappear, reconnect, and drift through degraded states.

MeshLink intentionally smooths that churn into a cleaner lifecycle so host apps do not need to reimplement transport timing policy themselves.

That is why a good integration should react to the emitted peer lifecycle instead of inventing a second timeout system in the UI layer.

For the lifecycle model itself, read [The peer lifecycle model](peer-lifecycle.md).

## Diagnostics belong in product-grade tooling

The best MeshLink integrations treat diagnostics as a first-class surface:

- development logging
- support tooling
- operator views
- issue reproduction notes
- in some products, even user-facing troubleshooting copy

That does not mean dumping every diagnostic into the primary UI. It means designing one place where those signals can be observed when things go wrong.

Because MeshLink redacts aggressively, diagnostics are also the safest place to reason about runtime behavior without turning logs into plaintext payload archives.

## Automatic power policy only works if you feed it reality

`PowerMode.Automatic` is useful because it keeps policy in shared code, but it depends on the host app telling MeshLink when battery conditions change.

If your product cares about power behavior, make `updateBattery()` part of your battery observation path. Otherwise, choose an explicit fixed `PowerMode` and own that decision deliberately.

For the policy model, read [Power management](power-management.md).

## Platform differences should stay at the edges

MeshLink is intentionally shared-first.

A clean integration keeps platform-specific logic at the edges:

- Android-specific bootstrap belongs at runtime creation, where `Context` is required
- iOS-specific crypto and optional BLE transport bridging belong in app-owned native startup code
- message, diagnostics, lifecycle, and routing behavior stay in shared app logic as much as possible

That split keeps your host app from re-implementing MeshLink's decisions on each platform.

## Use the right document for the right need

When teams say "the docs are missing," the real problem is often that the right information exists in the wrong form.

Use these docs by question type:

- **I want to learn by doing** → [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md)
- **I need to integrate this now** → [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- **I need exact API facts** → [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- **I need to understand the design** → the explanation docs in this section

That separation is deliberate. It keeps lessons teachable, guides actionable, reference precise, and explanations readable.
