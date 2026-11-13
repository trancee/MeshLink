# Forward unicast messages over multiple hops

Status: ready-for-agent
Type: AFK
Blocked by: 36

## Behavior

`send(to, payload)` reaches a peer that is not a direct neighbor, through relays. Every node relays unicast frames by their header alone, in every profile.

- **End to end, `Unsecured` only.** A secured `MESSAGE` needs an `IK` container, which lands in tickets 45 and 46. Here the origin sends `MESSAGE` (`0x40`) with container kind `0x04` (claimed `sender_node_id` in clear, ADR 0012 decision 12), addressed with unsecured tags.
- **Tags:** `unsecured_tag` per epoch. A node recognizes its own tags for epochs e − 1, e and e + 1.
- **Header:** the 34 B unicast header. `hop_limit` 0 is malformed. A relay decrements it and forwards only if the result is at least 1. Frames past `expiry` (allowing 30 s skew) are never forwarded. Frames more than 3,600 s + 30 s ahead are dropped.
- **Forwarding:** the six steps of the spec, in order: own tag → process; record a reverse route (6 s); drop a copy forwarded in the last 1,000 ms; forward on an active route only; else flood a small frame on every other link; drop a large frame without a route. Holding (step 5's MAY) is ticket 49.
- **Origin:** the low class floods with `hop_limit` 255. Medium and high send on an active route, or else flood with `hop_limit` 3 (`u6-flood-radius`). The parallel route request and the resend by new search are ticket 38. Retries double from 2 s to 30 s (low, medium) or 5 s to 60 s (high). Low and medium also retry on each new link. A retry keeps `message_id`, `expiry`, commitment and container, and re-tags for the current epoch.
- **Commitment:** the origin draws a 16 B `k`, puts it in the plaintext and `H(k)` in the frame. Tombstone use is ticket 51.
- **Destination:** processes each `message_id` once until expiry and emits one `ReceivedMessage` with the claimed sender.
- **Status:** `Queued`, then `Sent`, then `Expired` at expiry + 30 s. `Delivered` arrives with ticket 47. Until then, retries run on the schedule until expiry.
- **Unknown routed types** (`0x40`–`0x7F`) are relayed and never delivered.

The routed-unicast toggle (off = direct-only) is ticket 53; direct-only sends are ticket 47; large frames are ticket 50.

## Spec and ADR references

- [routing.md](../../../docs/spec/routing.md): [Routing tags](../../../docs/spec/routing.md#routing-tags), [Routing headers](../../../docs/spec/routing.md#routing-headers), [Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding), [Origin behavior](../../../docs/spec/routing.md#origin-behavior), [MESSAGE](../../../docs/spec/routing.md#message), [Routed frame summary](../../../docs/spec/routing.md#routed-frame-summary).
- [end-to-end.md, Containers](../../../docs/spec/end-to-end.md#containers) and [Plaintext](../../../docs/spec/end-to-end.md#plaintext) (`unsecured`: no padding).
- [D-08](../../../docs/spec/decisions.md#d-08-public-and-unsecured-tags), [D-27](../../../docs/spec/decisions.md#d-27-routing-header-and-hop-limit), [D-29](../../../docs/spec/decisions.md#d-29-duplicate-suppression).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decisions 3–6 and the ADR 0013 amendment; [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) decisions 5 and 12; [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 5 and 6; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 1; [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md) decisions 8 and 10.

## Acceptance criteria

- [ ] In `Unsecured`, any well-formed NodeId can be sent to: the unsecured tag derives from the NodeId alone, and route discovery (ticket 38) finds it. `Rejected(UnknownPeer)` applies only to a malformed NodeId (owner decision, 2026-11-13). A dated amendment to ADR 0018 decision 8 (version bump) records this, and ticket 12's `UnknownPeer` for a never-seen NodeId is migrated in this PR (O1). Tests send to a NodeId never seen before and to a malformed one; `python3 tools/check_adrs.py` passes.
- [ ] The agent reads the routing simulator to learn whether retries stop at the maximum interval or continue at it until expiry, and records the answer in the PR.
- [ ] Unicast header and `MESSAGE` codecs pass every matching `routed-frames.json` vector, including `acceptable` unknown types. The `unsecured_tag` derivation passes `derivations.json`. The unsecured plaintext passes `inner-plaintext.json`. Each new decoder has a deterministic mutation test.
- [ ] Seeded `VirtualAir` test, 6 nodes in a line, `Unsecured`, low class: A first links to F directly, the topology then changes to a line, and A's `send` to F arrives once, intact, with status `Sent` then `Expired`.
- [ ] The same line with the class pinned to medium: a destination 3 hops away is reached by the U6 flood; one 5 hops away is not (ticket 38 closes this).
- [ ] Seeded tests prove the 1,000 ms seen window, reverse routes expiring after 6 s, forwarding on an active route only, retry intervals per class, the retry on a new link and re-tagging across an epoch change.
- [ ] A seeded `Classical` mesh relays `routed-frames.json` `MESSAGE` frames with kinds `0x01` and `0x03` by header only, and drops kind `0x04`.
- [ ] T10: `hop_limit` 0, expired frames, expiry beyond 3,630 s, truncated headers and a disallowed container kind are dropped without state change.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/check_adrs.py
```

## Docs to update

- [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md): dated amendment narrowing `Rejected(UnknownPeer)` in decision 8 to a malformed NodeId (version bump).
- KDoc of `send`, `SendResult.Rejected` and `DeliveryStatus` for routed delivery in `Unsecured`.
- `CHANGELOG.md`: the narrowed `UnknownPeer`.
- [What each observer learns](../../../docs/reference/observer-exposure.md) only if the unsecured header exposure differs from what the page says.

## Devices needed

None. Host and simulator only. Ticket 43 proves a real line.
