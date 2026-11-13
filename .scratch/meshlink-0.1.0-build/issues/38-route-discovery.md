# Discover routes on demand

Status: ready-for-agent
Type: AFK
Blocked by: 37

## Behavior

In the medium and high classes, an origin without an active route finds one with `ROUTE_REQUEST` and `ROUTE_REPLY`. The metric is hop count with destination sequence numbers, in the style of AODV.

- **Origin:** without an active route, it sends the `u6-flood-radius` flood of ticket 37 and, at once, a `ROUTE_REQUEST` with `hop_limit` 64 and `expiry` 120 s ahead. After sending on a route, if no delivery receipt arrives within 1,000 ms + 200 ms × the route's hop count, it sends once more by a new search. Until ticket 47 brings receipts, that resend always fires.
- **Sequence numbers:** one `u32` per node, starting at 1, incremented before each request it originates and each reply it sends as target. RFC 1982 comparison; 0 is skipped.
- **Relays:** flood the request on with `hop_count` + 1, under flood duplicate suppression (by `message_id` until expiry), and record a reverse route to `origin_tag`.
- **Target:** the node that owns `target_tag` answers with `ROUTE_REPLY` and stops the request.
- **Intermediate reply:** a node with an active route to `target_tag` whose sequence is at least `target_sequence` MAY answer instead and stop the request.
- **Reply relays:** increment `hop_count` and record a forward route (60 s, then 30 s after last use). Newer sequence wins, then fewer hops.
- **No route error and no rate limit** in v1. Broken routes expire.
- **`peers`:** in `Unsecured`, a remote node with a route appears as `Routed(hops)`. In secured profiles, `Routed` also needs a valid certificate (ADR 0018 decision 9), so it lands in ticket 44.

This ticket adds the flood header codec unless ticket 39 merged it first.

## Spec and ADR references

- [routing.md, Route discovery](../../../docs/spec/routing.md#route-discovery): [ROUTE_REQUEST](../../../docs/spec/routing.md#route_request), [ROUTE_REPLY](../../../docs/spec/routing.md#route_reply), [Rules](../../../docs/spec/routing.md#rules); [Origin behavior](../../../docs/spec/routing.md#origin-behavior); [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding); [Routing headers](../../../docs/spec/routing.md#routing-headers) (flood header).
- [D-28](../../../docs/spec/decisions.md#d-28-route-request-and-reply), [D-29](../../../docs/spec/decisions.md#d-29-duplicate-suppression).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decisions 3 and 4 and the stage F amendment (no route-request rate limit).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decision 10 (`peers` reachability); [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md) decision 9.

## Acceptance criteria

- [ ] Flood header, `ROUTE_REQUEST` (`0x81`, 43 B) and `ROUTE_REPLY` (`0x43`, 47 B) codecs pass every matching `routed-frames.json` vector. Each new decoder has a deterministic mutation test.
- [ ] A unit test checks RFC 1982 comparison across the `u32` wrap and that 0 is skipped.
- [ ] Seeded `VirtualAir` test, 8 nodes in a line, `Unsecured`, class pinned to medium: a send 6 hops away arrives once, intact, through a discovered route. The test shows the reply's hop count and the forward route on each relay.
- [ ] A seeded test shows an intermediate reply from a node with a fresh enough route, and no reply from one with an older sequence.
- [ ] A seeded test breaks a link on the route, lets the route expire, and shows the resend by a new search reaching the destination over another path.
- [ ] A seeded test shows `peers` reporting `Routed(hops)` with the right hop count in `Unsecured`, and `Unreachable` after the route expires.
- [ ] T10: `origin_sequence` 0, `target_sequence` 0 in a reply, truncated and over-long frames, and a replayed request `message_id` are dropped without state change.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `Peer` reachability for `Routed(hops)` in `Unsecured`.

## Devices needed

None. Host and simulator only.
