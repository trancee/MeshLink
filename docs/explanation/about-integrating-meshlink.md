# About integrating MeshLink well

This page explains what good MeshLink integration looks like after the first
proof of concept works.

It is an explanation page, not a setup guide. Use these docs for the other job
shapes:

- setup and wiring — [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- production checklist — [How to structure a robust MeshLink integration](../how-to/structure-a-robust-meshlink-integration.md)
- runtime mental model — [About how MeshLink works](about-how-meshlink-works.md)
- exact public surface — [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)

## Treat MeshLink as an app-owned runtime

MeshLink works best when one long-lived app-owned object owns it.

That owner might be:

- an application service
- a controller
- a long-lived view model
- a shared app runtime object

Why this matters:

- discovery gets time to converge
- trust continuity survives across many sends
- diagnostics tell one coherent story
- retry and peer state stay attached to the same runtime

The main anti-pattern is creating a fresh runtime per screen visit or per send.
That throws away exactly the state that makes MeshLink useful.

## Treat `appId` as a mesh boundary

`appId` is not display text. It defines which peers belong to the same logical
mesh.

A healthy setup usually means:

- one production `appId` for production peers
- one staging `appId` for staging peers
- one isolated `appId` for proof, lab, or benchmark work

When discovery looks wrong, check `appId` early. A mismatched `appId` often
looks like "nothing is happening" rather than like a clear error.

## Keep the four public streams separate

MeshLink exposes four public streams because each answers a different question:

- `state` — what is the runtime doing right now?
- `peerEvents` — which peers are visible and how did that change?
- `diagnosticEvents` — what is the runtime machinery doing?
- `messages` — which application payloads arrived?

Good integrations keep those responsibilities separate in app state.

A common failure mode is to look only at `messages`. That works until something
fails and nobody can explain whether the problem was discovery, trust,
routing, power policy, or delivery.

## Model delivery honestly

`SendResult.Sent` means MeshLink completed the delivery path it owns.

It does **not** mean that the remote app:

- stored the payload
- showed it to a user
- completed a business action
- sent an application-level acknowledgement

If your product needs that stronger meaning, add an application-level receipt
or response on top of MeshLink.

## Make trust reset a visible product action

MeshLink uses local continuity, not central account identity. That makes trust
reset a product decision, not just a transport detail.

A good integration answers these questions explicitly:

- who is allowed to forget a peer?
- what happens to labels, conversation history, or device metadata afterward?
- how is a trust failure explained to the user or operator?

A bad integration silently calls `forgetPeer()` in ordinary retry logic. That
turns a meaningful identity event into hidden mutable state.

For the underlying model, read [The trust model](trust-model.md).

## Let MeshLink own peer smoothing

BLE peer visibility is noisy. Peers disappear briefly, reconnect, and drift
through degraded transport states.

MeshLink intentionally smooths that churn into a cleaner lifecycle so host apps
do not have to invent their own timers and grace windows.

That is why a good integration reacts to `PeerEvent`s instead of layering a
second peer timeout system on top of them.

For the details, read [The peer lifecycle model](peer-lifecycle.md).

## Give diagnostics a real home

Diagnostics are part of a production-shaped integration, not just a debugging
extra.

Useful places for them include:

- development logs
- operator tooling
- support screens
- issue-reproduction notes
- user-facing troubleshooting copy in products that need it

This does **not** mean that every diagnostic belongs in the main UI. It means
there should be one deliberate place to inspect runtime behavior when things go
wrong.

## Use automatic power mode only when you can support it

`PowerMode.Automatic` is useful, but it depends on the host app feeding real
battery updates into MeshLink.

If your app already owns battery observation, wire `updateBattery()` into that
path.

If it does not, choose a fixed `PowerMode` instead. That is often the cleaner
and more honest choice.

For Android direct-proof sessions on doze-sensitive devices, prefer the
reference app's live-proof foreground wake-lock mitigation or a deliberate
screen-awake/battery-optimization policy rather than assuming passive BLE
scanning will stay alive on its own. Keep that mitigation in the app shell or
operator flow, not buried in a one-off runner flag.

For the policy model, read [Power management](power-management.md).

## Keep platform code at the edges

MeshLink is shared-first. A clean integration keeps platform-specific work near
startup and bootstrap:

- Android-specific bootstrap stays where `Context` is available
- iOS-specific crypto and optional BLE bridge installation stay in native app
  startup
- message handling, lifecycle observation, and most product logic stay in
  shared app code when possible

That split keeps platform glue small and keeps MeshLink behavior consistent
across Android and iOS.

## What good looks like

A production-shaped MeshLink integration usually has all of the following:

- one long-lived runtime owner
- one deliberate `appId` per environment
- separate handling for `state`, `peerEvents`, `diagnosticEvents`, and
  `messages`
- application-level receipts if the product needs stronger guarantees than
  transport success
- explicit trust reset UX or operator flow
- a visible diagnostics surface
- a deliberate power-policy choice, including a clear stance on doze-sensitive Android direct-proof sessions
- platform-specific bootstrap code kept at the edges

## Related docs

- [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- [How to structure a robust MeshLink integration](../how-to/structure-a-robust-meshlink-integration.md)
- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [About how MeshLink works](about-how-meshlink-works.md)
- [The trust model](trust-model.md)
- [Power management](power-management.md)
