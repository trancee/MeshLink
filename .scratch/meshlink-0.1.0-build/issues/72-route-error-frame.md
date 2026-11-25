# Decide whether relays report broken routes

Status: needs-triage
Type: HITL
Blocked by: 50

## Behavior

Found in ticket 50 (D-58). A relay that has no route for a large frame drops it silently, so the origin learns of a broken route only when its retry gets no receipt. D-58 makes every timed retry of a large message search anew and uses found routes only while they are fresh, which delivers the message but spends a route request per retry and a full retry interval per break. A route-error frame, as in AODV's RERR (RFC 3561 §6.11), would tell the origin at once. Decide whether MeshLink adds one.

Questions for the owner:

- Is a frame that names the unreachable destination tag acceptable for privacy (ADR 0016's rotating tags)?
- Does it travel the reverse route only, and how is it rate-limited and authenticated against forged route breaks?
- Does it replace D-58's per-retry search, or only shorten the wait?

## Spec and ADR references

- [routing.md, Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding) (step 6) and [Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-58](../../../docs/spec/decisions.md#d-58-fresh-routes-for-large-messages).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) (route discovery); [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) (routing tags and metadata).
- [RFC 3561](https://www.rfc-editor.org/rfc/rfc3561) §6.11, route error messages.

## Acceptance criteria

- [ ] A seeded `VirtualAir` comparison of D-58's per-retry search against a prototype route-error frame: route requests sent, delivery latency after a break, and airtime, on the diamond of `LargeMessageTest` and a 50-node low-density run.
- [ ] The owner decides; the decision is recorded as a D-nn entry and, if adopted, as a new frame in the spec with vectors and an ADR 0008 amendment.
- [ ] If adopted, an AFK ticket implements it.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*LargeMessage*'
```

## Docs to update

- `docs/spec/decisions.md`, `docs/spec/routing.md` and ADR 0008 if adopted.

## Devices needed

None.
