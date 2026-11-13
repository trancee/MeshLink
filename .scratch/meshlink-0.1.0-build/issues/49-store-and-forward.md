# Hold messages for absent recipients

Status: ready-for-agent
Type: AFK
Blocked by: 47

## Behavior

A message to a recipient that is out of reach survives within its lifetime. The origin keeps its own copy and retries; relays keep best-effort copies and hand them over when they meet the recipient or a route to it.

- **Custody:** the origin keeps its copy until `Delivered` or `Expired`. There is no custody transfer and no copy budget.
- **Holding for others:** a relay MAY hold a small unicast or broadcast frame and forwards it again on a new link or a new route. Bounds: at most 100 frames and 524,288 B, at most ¼ of each from one incoming link; only small frames (payload at most 4,096 B) with at least 10 s of lifetime left that arrived with `hop_limit` 2 or more. Never past expiry.
- **Eviction:** expired frames first. When full, evict from the link furthest over its share; otherwise a new frame replaces the held frame closest to expiry, only if the new one has more lifetime left.
- **Memory only:** held frames and outgoing status are lost on `stop()` or process death.
- **Broadcasts:** held under the same quota until they expire. No receipts, no tombstones.
- **Encounter hand-off:** on each new link, the `CATCH_UP` held list carries `{message ID, destination tag}` for held unicasts, newest first, at most 100. The receiver grafts entries whose destination tag is its own for any epoch within the last 3,600 s, or that it has an active route to. The holder forwards the frame unchanged except for the hop limit.
- **Eagerness:** low and medium retry on every new link, at most once per 5 s per destination, and on backoff. High retries on backoff only. A holder flushes at once when the destination's own route request passes.
- **Statuses:** the origin reports `Held` while it has no route and waits for one, alternating with `Sent`; `Expired` at expiry + 30 s.
- **Lifetime:** the origin sets expiry 120–3,600 s ahead. The preset values and the holding toggle are ticket 53; until then, internal defaults with holding on.

Deleting held copies by tombstone is ticket 51.

## Spec and ADR references

- [routing.md, Holding](../../../docs/spec/routing.md#holding), [Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding) (step 5) and [Routing headers](../../../docs/spec/routing.md#routing-headers) (lifetime bounds).
- [link.md, Catch-up](../../../docs/spec/link.md#catch-up) (held list and claims); [end-to-end.md, Conversation tags](../../../docs/spec/end-to-end.md#conversation-tags) (claims across epochs).
- [ADR 0014](../../../docs/adr/0014-store-and-forward.md) decisions 1–7 and 11; [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 5, 6 and 9; [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decision 7; [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 5 and the 2026-11-03 amendment.
- [v1 parameters, Message lifetime, hop limit and store-and-forward](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#message-lifetime-hop-limit-and-store-and-forward) (eagerness).

## Acceptance criteria

- [ ] Seeded `VirtualAir` test, `Unsecured` and `Classical`: E is absent while A sends to it; a relay holds the frame; E returns within the lifetime and receives once, intact, through the held list and `GRAFT`; A reaches `Delivered`.
- [ ] A seeded test with A also gone when E returns: E still receives the relay's copy.
- [ ] A seeded `Classical` test holds a first message to E's public tag and a later session frame to a conversation tag; E claims both after an epoch change.
- [ ] Unit tests hit every bound: the 101st frame, byte 524,289, the ¼ link share, a 4,097 B payload, 9 s of lifetime left and `hop_limit` 1. Each follows the eviction order.
- [ ] A seeded test shows held broadcasts under the same quota, delivered to a node that returns.
- [ ] Seeded tests check per-class eagerness and the flush on a passing route request.
- [ ] A test shows `stop()` drops every held frame.
- [ ] Ticket 42's pull-request suite gains store-and-forward scenarios (a relay goes offline and returns) and still fits its time budget.
- [ ] T10: a link that floods frames fills at most its ¼ share; expired or over-long-lived frames are never held.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `DeliveryStatus.Held` and of message lifetime.
- [GLOSSARY.md](../../../GLOSSARY.md) only if "Store-and-forward" disagrees with the code.

## Devices needed

None. Host and simulator only.
