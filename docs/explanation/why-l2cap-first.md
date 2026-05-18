# About the L2CAP-first transport posture

MeshLink still thinks in L2CAP-first terms, even though the current mixed
Android/iOS product path can use an iPhone-hosted GATT-notify side bearer for
part of the large-transfer path.

That distinction matters. The newer mixed-bearer path did not replace the
original transport idea with a GATT-first design. It refined the edges of that
idea after physical evidence showed where the platform boundary was actually
costly.

## The stable idea

L2CAP remains the preferred direct bearer because it matches the kind of work
MeshLink is doing:

- it gives the runtime a stream-shaped channel instead of attribute traffic
- it avoids the service-discovery round trip before useful data can flow
- it reduces protocol overhead for large payloads
- it gives both peers a more symmetric transport model

For a mesh SDK, those properties are not small optimizations. They shape how the
runtime schedules delivery, retries, framing, and session establishment.

## Why the current product path is not GATT-first

The mixed-bearer follow-up was driven by physical Android/iOS behavior, not by a
change in the product's mental model.

The runtime still prefers to discover, identify, and connect peers in an
L2CAP-first way. What changed is that some mixed-platform links proved more
stable and faster when the iPhone stayed in the peripheral-hosted role for the
large-transfer drain path.

So the current posture is:

- same-platform peers should still look like direct L2CAP peers
- mixed Android/iOS peers still begin from the L2CAP-first transport policy
- the runtime may then select the iPhone-hosted GATT-notify side bearer where
  that path is measurably better for the data plane

That is a refinement of an L2CAP-first design, not a retreat from it.

## Why MeshLink never wanted to be GATT-only

GATT is excellent when the problem is attribute access: read a value, write a
value, subscribe to a characteristic.

MeshLink is solving a different problem. It needs to move framed encrypted mesh
traffic, sometimes in large bursts, while keeping the application model stable.

A pure GATT design would push too much transport bookkeeping into the slowest
and least symmetric part of the system:

- service discovery before useful work
- explicit notification plumbing
- more flow-control edge cases
- more fragmentation pressure on large transfers
- more platform-specific handling in places that should stay shared

That is why L2CAP stayed the architectural default even when later proof work
showed that a GATT side bearer could help on one mixed-platform edge.

## Why discovery still carries L2CAP intent

MeshLink wants peers to know enough about each other before they commit to a
connection attempt.

That is why discovery still advertises whether an L2CAP path is available and
still feeds the deterministic initiator policy. The runtime should be able to
prefer the better direct bearer without first paying for a provisional GATT
session just to learn what kind of peer it found.

This is one of the reasons the mixed-bearer path can coexist with an L2CAP-first
posture: discovery, peer matching, and connection-initiation policy still begin
from the same direct-link model.

## Where the mixed-bearer side path fits

The mixed-bearer path exists because the platform boundary is asymmetrical.

On the current reference matrix, the decisive large-transfer improvement came
from keeping the iPhone in the role it handled best while letting Android own
connection initiation and the rest of the mixed-platform coordination.

That means the side bearer is best understood as a targeted transport adaptation:

- it preserves the shared peer, trust, and routing model
- it does not ask host apps to pick a bearer per message
- it lets the runtime hide a platform-specific transport compromise behind one
  application-facing `send()` contract

This is why integrators should think in terms of "MeshLink chooses the best link
shape for this peer pair" rather than "my app is using GATT mode now".

## OEM and platform reality still matter

The transport posture is shaped not only by theory but by the BLE stacks that
actually ship:

- some device classes handle L2CAP more reliably than others
- some mixed-platform combinations prefer one initiator/peripheral split over
  another
- some large-transfer paths look fine in protocol design and still lose in
  physical throughput or stability

The later mixed-bearer work is a reminder that transport design is not only
about protocol elegance. It is also about finding the cleanest architecture that
still respects what the radios and operating systems really do.

## What this means for integrators

Host applications should still treat MeshLink as a runtime that owns transport
selection.

The application contract does not change because the runtime chooses a different
bearer for a given peer pair. You still create one runtime, observe the public
streams, and send payloads through the same API.

If you need the practical setup steps, use [How to integrate MeshLink into a
host app](../how-to/integrate-meshlink-into-a-host-app.md). If you need the
measured physical status, use the retained [benchmark and validation
baselines](../../benchmarks/README.md).
