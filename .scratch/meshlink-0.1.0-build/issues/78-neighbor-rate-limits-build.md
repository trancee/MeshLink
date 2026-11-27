# Rate-limit relayed flood frames per neighbor

Status: ready-for-agent
Type: AFK
Blocked by: 74

## Behavior

Ticket 74 decided (D-63) that a relay limits the flood frames each neighbor may make it relay. Each incoming link has a token bucket per frame type, refilled continuously at rate R per second up to a burst of B.

| Frame | Low class | Medium and high class |
|---|---|---|
| `BROADCAST`, per-link total | 4/s, burst 20 | 4/s, burst 20 |
| Signed broadcast (kind `0x05`, secured profiles), per `origin_key` | 1/s, burst 5 | 1/s, burst 5 |
| Flooded unicast (sent to every neighbor) | 5/s, burst 20 | 10/s, burst 40 |
| `ROUTE_REQUEST` | 5/s, burst 20 | 5/s, burst 20 |
| `LOOKUP_REQUEST` | 5/s, burst 20 | 5/s, burst 20 |

Rules:

- A frame needs a token from every bucket that applies. A signed broadcast needs one from its origin bucket and one from the link's total bucket. A frame over budget takes no tokens.
- An over-budget frame is not relayed. It is still delivered locally if it is addressed to this node, and its message ID is still recorded as seen.
- The class is the node's current density class.
- Buckets start full when a link opens and are dropped when it closes.
- A link keeps at most 32 origin buckets (`rate-origins-per-link`), matching the 32 relayed frames a lane holds. A new origin evicts the least recently used bucket, so memory stays bounded. The prototype had no bound, so the value is unmeasured. An evicted origin restarts with a full bucket, and the per-link total still caps it.
- Unicast sent on a known route (one next hop) is not limited.
- Every drop emits a new Debug diagnostic, `ML5005` `RelayRateLimited(peer, frame)`, coalesced per peer under the ticket 54a rules.
- The values are fixed spec parameters in `limits.json`. There is no public API.

## Spec and ADR references

- [Ticket 74](74-neighbor-rate-limits.md), prototype verdict; prototype branch `prototype/neighbor-rate-limits`, commit `628fd7a`.
- [routing.md, Flood forwarding](../../../docs/spec/routing.md#flood-forwarding); line 246 ("v1 has no route-request rate limit") is replaced.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md): a new amendment supersedes "Route-request rate limit".
- [Threat model, Availability](../../../docs/explanation/threat-model.md#availability): replace the "not built yet" note. Per-origin fairness holds only in the secured profiles, and Sybil keys fall back to the per-link total.
- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) and `docs/agents/diagnostics.json`: the new code `ML5005`.

## Acceptance criteria

- [x] D-63 is in `decisions.md`. `routing.md` and `limits.json` carry the parameters, and ADR 0008 has the amendment.
- [x] The threat model's Availability section and the CHANGELOG match.
- [x] Unit tests prove each bucket: refill, burst, origin-bucket eviction, local delivery of an over-budget frame, and the class switch.
- [x] A `VirtualAir` test shows a neighbor flooding broadcasts at 20/s being cut to the per-link rate, with `ML5005` emitted.
- [x] `ML5005` is in the diagnostics reference, the generated troubleshooting tables and the diagnostics wiring tests.
- [ ] One `density.yml` run of all 8 cells with 5 seeds passes every band, which proves the high-class values.
- [x] All gates pass, with Kover at 100%.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest :meshlink-testing:testAndroidHostTest
gh workflow run density.yml --ref feat/neighbor-rate-limits
```

## Devices needed

None.
