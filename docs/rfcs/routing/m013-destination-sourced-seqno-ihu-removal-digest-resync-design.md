# M013 destination-sourced route freshness, IHU cost signal removal, and
digest-triggered resync

Status: Draft (design spec for issue #130, informed by
[m012-destination-sourced-seqno-and-ihu-digest-research.md](m012-destination-sourced-seqno-and-ihu-digest-research.md))

This is a design spec, not an implementation. It records the decisions made
for issue #130 and the reasoning behind them, so implementation can proceed
against a fixed target rather than being designed inline. Where a decision
revises what was originally agreed for #130 (see #130's own comment
history), that revision and its rationale are called out explicitly.

## Scope

This spec covers three related but separable pieces of MeshLink's
Babel-inspired routing (`routing/RouteCoordinator.kt` and friends):

1. How a route's sequence number is originated and kept fresh across BLE
   reconnect churn.
2. What (if anything) replaces the currently-dead `Hello`/`Ihu` wire frames.
3. What happens when `RouteDigestTracker`'s digest exchange detects a
   mismatch between two peers' route tables.

It does not redesign trust, identity, transport bearer selection, or the
feasibility condition itself (already correct per RFC 8966 §2.4/§3.5.1 and
unaffected by anything below).

## 1. Route freshness: destination-self-reported seqno, not a request/response round-trip

### The actual bug, restated precisely

The bug that started this whole line of work was never "MeshLink doesn't
implement RFC 8966's seqno mechanism." It's narrower and more mechanical:
**the wrong node mints the sequence number.**

Today, `RouteCoordinator.onPeerConnected` computes
`directRouteSeqNos[peerId] + 1` and stores it as the seqno for "the direct
route to `peerId`" — from the perspective of whichever node happens to be
directly connected to `peerId` right now. Two different direct neighbors of
the same destination, connecting at different times, each independently
mint their own, unrelated seqno sequence for "the route to that
destination." Relays never touch a received route's `seqNo` (they forward
it unchanged), so the value genuinely originates at whichever node most
recently connected directly to the destination — not at the destination
itself. That is why a relay can report a numerically higher seqno for a
peer than the peer's own currently-live direct connection has, and
displace it.

### Why RFC 8966's literal fix (request/response) doesn't fit here

RFC 8966's actual mechanism (research findings #1-#11) is: a destination's
own seqno only ever increases by exactly 1, and only in direct reaction to
an explicit `SeqNoRequest` that must reach the destination itself
(forwarded hop-by-hop if necessary, bounded by a hop-count field). This
assumes the destination is reachable, at least eventually, for that round
trip to complete.

MeshLink cannot assume that. BLE devices disconnect and reconnect
constantly, and `specs/ble-mesh-sdk/spec.md` requires convergence within 3
seconds for a 10-node topology change. A request that has to traverse
multiple hops to reach a destination that may itself be mid-reconnect, and
come back, is not a mechanism this network can lean on for ordinary route
freshness — and, per the research, no production system solves "Babel over
a churny link" by keeping that round trip; both close analogues
(batman-adv, Bluetooth Mesh) abandon it.

### Decision: destination self-reports and increments its own seqno counter

Each node owns exactly one local seqno counter (matching RFC 8966 §3.7's
"the seqno is set to the local router's sequence number" — one counter per
originating node, not one per route or per connection). A node increments
its own counter only on a **cold start of its own mesh participation**
(engine start / fresh `MeshLink.start()`), not on every individual peer
reconnect — this is the batman-adv-style self-refresh, and it directly
targets the freshness concern `docs/explanation/understanding-babel-routing.md`
already raised ("if reconnect uses stale sequence information... neighboring
nodes keep older route state") without minting a new value per neighbor per
reconnect.

**Wire mechanism**: reuse the existing `RouteUpdate` frame type as a
self-origin announcement. Immediately after a hop session is established
with a new direct neighbor, each side sends one `RouteUpdate` describing
itself: `destinationPeerId = <own peerId>`, `nextHopPeerId = <own peerId>`,
`metric = DIRECT_ROUTE_METRIC`, `seqNo = <own current counter value>`. This
requires no new wire frame type or field — `RouteCoordinator.onRouteUpdate`
already accepts route updates from a peer about a third destination; a
self-origin update (destination == the sender) is a new case to handle
explicitly, not new wire surface.

**Receiving side's obligation**: `onPeerConnected` no longer mints a seqno
directly. It installs the direct route provisionally (metric/keys as today)
and, on receiving the new peer's self-origin `RouteUpdate`, adopts the
reported `seqNo` as that peer's authoritative value going forward — the
same way any other peer's self-reported route already flows through
`onRouteUpdate` today. If the self-origin update never arrives (peer
disconnects before sending it), the provisional direct route falls back to
whatever the existing feasibility/expiry logic already does for stale
routes — no new failure mode is introduced.

**What this fixes**: the untested edge case from the earlier review (relay
reports a higher seqno than a live direct route) becomes structurally
impossible in the common case, because every neighbor of a given
destination converges on the *same* self-reported value from that
destination, instead of each minting its own.

**What stays out of scope**: `SeqNoRequest`-based starvation recovery
(RFC 8966 §3.8.2.1) remains unimplemented. It was never MeshLink's actual
recovery mechanism (the reconnect-driven bump substituted for it), and this
design's cold-start self-refresh continues to serve that role. If a future
need for the explicit request/response path materializes, it requires its
own wire-format change (a hop-count field, per research finding #11) and
its own design pass — not bundled here.

## 2. Hello/IHU: remove, don't implement RFC-literal periodic liveness

RFC 8966 §3.4 explicitly permits skipping Hello-based neighbor discovery
"if neighbour discovery is performed by means outside of the Babel
protocol" (research finding #12, #14). BLE's own transport already
provides exactly that: a GATT/L2CAP connection establishing or tearing down
*is* the neighbor-liveness signal, delivered immediately, with no
periodic-interval polling needed.

Building RFC-literal Hello/IHU (per-interface/per-neighbor Hello seqno
counters, IHU hold-timers, rxcost/txcost exchange) on top of a transport
that already tells you the instant a link goes up or down is real protocol
complexity solving a problem BLE already solves. It would also require
wire-format additions the current `Hello`/`Ihu` frames don't have (a Hello
seqno counter, an IHU interval field — research finding #18) purely to
support liveness-timer semantics that duplicate what `onPeerConnected`/
`onPeerDisconnected` already deliver for free.

**Decision (revises the earlier "wire them up" call for #130): remove**
`WireFrame.Hello`, `WireFrame.Ihu`, their envelope type codes, and their
no-op dispatch branches in `MeshEngineInboundSupport`. This is dead
protocol surface with no RFC-mandated reason to exist given MeshLink's
actual transport, and removing it is simpler and more honest than
maintaining wire-compatible-forever encode/decode support for messages
nothing will ever send. Route metric stays a flat `+1` hop count, as today
— a real link-cost signal (bearer type, RSSI) is a separate, independently
justifiable feature, not a Babel-compliance requirement, and is explicitly
out of scope for this spec.

## 3. RouteDigest: on mismatch, push a full local table resend — no new differential protocol

RFC 8966 has no route-table digest mechanism at all (research finding
#19-#21), so there is no spec to match here, only a design to justify
against MeshLink's own goals.

`RouteDigestTracker` already computes and attaches a cheap 32-bit FNV-1a
digest of the local route table to nearly every advertisement. The missing
piece is entirely on the receive side: `RouteCoordinator.onRouteDigest` is
currently a literal no-op.

**Decision**: on receiving a `RouteDigest` frame whose value doesn't match
the receiver's own locally-computed digest, the receiver re-sends its
**full** current route table to that one peer via the existing
`RouteAdvertisementPlanner` advertisement path (the same set of
`RouteUpdate` frames already built for the `forPeerConnected` case) —
mirroring RFC 8966's own closest analogous fallback (a wildcard route
request gets a full table dump, not a differential one; research finding
#20). This requires no new wire frame type, no new field, and no
request/response round trip: whichever side notices the mismatch pushes,
which is safe to do redundantly if both sides happen to notice at once.

This is intentionally the simplest correct behavior, not a
bandwidth-optimized differential sync. If a future measurement shows this
push-on-mismatch behavior costs meaningfully more control-plane bandwidth
than a true differential protocol would justify, that's a separate,
data-driven follow-up — not a speculative optimization to build now.

## Summary of wire-format impact

| Frame | Before this spec | After this spec |
|---|---|---|
| `RouteUpdate` | destination-route announcement | also used for self-origin announcements (destination == sender) on connect |
| `Hello` | dead, encoded/decoded, no-op on receipt | removed |
| `Ihu` | dead, encoded/decoded, no-op on receipt | removed |
| `SeqNoRequest` | dead, encoded/decoded, no-op on receipt | remains dead, explicitly deferred (not removed — kept as reserved wire surface for a possible future starvation-recovery design, since unlike Hello/IHU it isn't solved by an existing MeshLink mechanism) |
| `RouteDigest` | sent on nearly every advertisement, receive side no-op | receive side triggers a full-table push to the mismatched peer |

Since no MeshLink release has shipped yet (`CHANGELOG.md`), removing
`Hello`/`Ihu` is not a breaking wire-format change requiring a major version
bump — it's cleanup before the wire format is ever deployed.

## Testing requirements for implementation

- `RouteCoordinatorTest`: add the previously-missing case (relay reports a
  higher self-reported seqno than a live direct route) and confirm it no
  longer displaces the direct route under the new self-origin model.
- `MeshTestHarness`/`VirtualMeshTransport` (the canonical multi-node
  harness, per the constitution): a reconnect-churn scenario proving two
  different neighbors of the same destination converge on the same
  self-reported seqno, and a digest-mismatch scenario proving a full-table
  push resolves a deliberately desynced pair of nodes.
- Wire-compat fixtures (`commonTest/resources/wire-compat/`): drop the
  `v1_hello.hex`/`v1_ihu.hex` fixtures added in #120's fixture-widening pass
  along with the frame types themselves; keep `v1_seqno_request.hex` since
  that frame type is deferred, not removed.
- Routing convergence benchmark/test: confirm the 3-second/10-node
  convergence budget still holds with the self-origin announcement added to
  the connect path (it should — it adds one extra frame per new connection,
  not a round trip).

## Related docs

- [Understanding Babel routing in MeshLink](../../explanation/understanding-babel-routing.md)
- [M012 research: destination-sourced seqno, Hello/IHU, and RouteDigest design space](m012-destination-sourced-seqno-and-ihu-digest-research.md)
- [RFC 8966 (vendored)](rfc8966.txt)
