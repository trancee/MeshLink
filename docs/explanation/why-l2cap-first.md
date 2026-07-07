# About the L2CAP-preferred transport posture

MeshLink keeps L2CAP as the preferred channel-oriented bearer for normal
connections, while keeping GATT active at the same time whenever the platform
supports it. Android and iOS now run the GATT side bearer alongside L2CAP so
older peers can still connect through GATT while newer peers continue to prefer
L2CAP when both are available.

That distinction matters. The mixed-bearer work did not replace the transport
model with a GATT-first design. It refined the edges of an L2CAP-preferred
design after physical evidence showed where the platform boundary was actually
costly.

## The stable idea

L2CAP remains the preferred direct bearer because it matches the kind of work
MeshLink is doing:

- stream-shaped transport instead of attribute traffic
- no service-discovery round trip before useful data flows
- lower overhead for large payloads
- a more symmetric peer model

For a mesh SDK, those are architectural properties, not minor optimizations.

## Why the current product path is not GATT-first

The mixed-bearer follow-up was driven by measured Android/iOS behavior, not by a
new product philosophy.

The runtime still prefers to discover, identify, and connect peers with
L2CAP as the default direct bearer. What changed is that GATT now stays active
at the same time whenever the platform supports it, so a peer can still connect
through the GATT side bearer when L2CAP is unavailable or not yet ready.

So the current posture is:

- same-platform peers should still look like direct L2CAP peers
- mixed Android/iOS peers keep GATT available alongside L2CAP
- the runtime may prefer the GATT side bearer where that path is needed for
  compatibility or fallback delivery

That is a refinement of an L2CAP-preferred design, not a retreat from it.

## Why MeshLink never wanted to be GATT-only

GATT, the Generic Attribute Profile, is excellent for attribute-style work:
reading values, writing values, and subscribing to characteristics.

MeshLink is solving a different problem. It needs to move framed encrypted mesh
traffic, sometimes in large bursts, while keeping one stable application model.

A pure GATT design would push too much transport bookkeeping into the least
symmetric part of the system:

- service discovery before useful work
- explicit notification plumbing
- more fragmentation pressure on large transfers
- more platform-specific behavior in places that should stay shared

## Why discovery still carries L2CAP intent

Discovery still advertises whether an L2CAP path is available and still feeds
the deterministic initiator policy.

That lets the runtime prefer the better direct bearer without first paying for a
provisional GATT session just to learn what kind of peer it found.

## Where the mixed-bearer path fits

The mixed-bearer path is best understood as a targeted transport adaptation:

- it preserves the shared peer, trust, and routing model
- it does not ask host apps to choose a bearer per message
- it lets the runtime hide a platform-specific compromise behind one public
  `send()` contract

Integrators should think in terms of "MeshLink chooses the best link shape for
this peer pair," not "my app is in GATT mode now."

## What this means for integrators

Host applications should still treat MeshLink as a runtime that owns transport
selection.

The application contract does not change because the runtime chooses a different
bearer for a given peer pair. You still create one runtime, observe the public
streams, and send payloads through the same API.

If you need the practical setup steps, use
[How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md).
If you need the measured physical status, use the retained
[benchmark and validation baselines](../../meshlink-benchmark/README.md).

## Related docs

- [About how MeshLink works](about-how-meshlink-works.md)
- [Cut-through relay](cut-through-relay.md)
- [Physical reference-app integration findings](reference-app-physical-integration-findings.md)
- [Benchmark and validation baselines](../../meshlink-benchmark/README.md)
