# About the L2CAP-first transport posture

MeshLink still thinks in L2CAP-first terms, where L2CAP is the
channel-oriented BLE bearer MeshLink prefers, even though the current mixed
Android/iOS product path can use an iPhone-hosted GATT-notify side bearer for
part of the large-transfer path.

That distinction matters. The mixed-bearer work did not replace the transport
model with a GATT-first design. It refined the edges of an L2CAP-first design
after physical evidence showed where the platform boundary was actually costly.

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

The runtime still prefers to discover, identify, and connect peers in an
L2CAP-first way. What changed is that some mixed-platform links proved more
stable and faster when the iPhone stayed in the peripheral-hosted role for the
large-transfer drain path.

So the current posture is:

- same-platform peers should still look like direct L2CAP peers
- mixed Android/iOS peers still begin from the L2CAP-first policy
- the runtime may then select the iPhone-hosted GATT-notify side bearer where
  that data-plane path is measurably better

That is a refinement of an L2CAP-first design, not a retreat from it.

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
[benchmark and validation baselines](../../benchmarks/README.md).

## Related docs

- [About how MeshLink works](about-how-meshlink-works.md)
- [Cut-through relay](cut-through-relay.md)
- [Physical reference-app integration findings](reference-app-physical-integration-findings.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
