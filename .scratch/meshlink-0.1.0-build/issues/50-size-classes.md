# Send large messages within their size classes

Status: ready-for-agent
Type: AFK
Blocked by: 28, 37

## Behavior

`send` carries payloads up to 128 KiB across the mesh. Small and large messages follow different routing rules, and every cap holds in every profile.

- **Classes:** a small message has at most 4,096 B of payload; a large one more than 4,096 B and at most 131,072 B. Caps count app payload only; handshake, `post-quantum` and padding overhead never count.
- **Relays:** see only frame sizes. A routed frame above 8,192 B (`frame-small-max`) is large: never flooded or held, forwarded only on an active route, dropped otherwise. Frames are at most 139,264 B (`frame-max`).
- **Origin:** sends a large message only on a direct link or on a route found or confirmed by route request. At low density it sends the route request first. It alone holds and retries large messages, on its class's retry schedule.
- **Per hop:** frames are reassembled at every hop with ticket 28's fragments, reassembly limits and busy reply. On busy, the sender backs off and retries within its schedule. A relay that cannot pass a large frame on before its reassembly window ends drops it; the origin's retry covers it.
- **Queues:** the send queue holds at most 1 MiB; above that, `send` returns `Rejected(QueueFull)` and `awaitSendCapacity()` resumes when space frees. The inbox holds at most 1 MiB, at most ¼ per sender; a full inbox stops acknowledging. Earlier tickets may already enforce these; this ticket proves them at large sizes.
- **Config:** `limits.maxUnicastPayload` (4 KiB to 128 KiB) and `limits.maxBroadcastPayload` (at most 1 KiB) may only lower the caps. A send above the configured cap returns `Rejected(TooLarge)`. `config.limits` reports the effective caps. Preset values and `describe()` output are ticket 53's.
- **No compression:** MeshLink neither compresses nor decompresses.

## Spec and ADR references

- [routing.md, Size classes](../../../docs/spec/routing.md#size-classes), [Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding) (step 6) and [Origin behavior](../../../docs/spec/routing.md#origin-behavior).
- [link.md, Link records](../../../docs/spec/link.md#link-records), [Fragments](../../../docs/spec/link.md#fragments) and [Busy reply](../../../docs/spec/link.md#busy-reply).
- [D-19](../../../docs/spec/decisions.md#d-19-record-and-frame-size-thresholds), [C-05](../../../docs/spec/decisions.md#c-05).
- [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decisions 1, 3, 4 and 6–8; [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 7 and the 2026-11-03 message-size amendment; [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 7 and 9 and its send-capacity, limits and inbox amendments.

## Acceptance criteria

- [ ] Seeded `VirtualAir` tests, 5-node line, in all three profiles, medium class: 4,096 B, 4,097 B and 131,072 B payloads arrive once, intact; the large ones travel only on a discovered route.
- [ ] The same at low class: the origin sends the route request first and never floods the large frame.
- [ ] A seeded test breaks the route: relays drop the large frame, never hold it, and the origin's retry delivers it over a new route.
- [ ] A seeded test fills the next hop's reassembly budget: the busy reply makes the origin back off and the message still arrives.
- [ ] A 131,073 B payload returns `Rejected(TooLarge)`; so does 4,097 B after lowering `maxUnicastPayload` to 4 KiB. Raising a cap above the maximum fails config validation.
- [ ] A test fills the send queue to 1 MiB, gets `Rejected(QueueFull)`, and `awaitSendCapacity()` resumes after the queue drains.
- [ ] The largest `PostQuantum` first message with a 131,072 B payload fits within 139,264 B; a test pins the size.
- [ ] T10: a relayed frame above 8,192 B without a route and any frame above 139,264 B are dropped without state growth.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `send`, `config.limits`, `Rejected(TooLarge)`, `Rejected(QueueFull)` and `awaitSendCapacity()`, including the compression warning of ADR 0015 decision 4. The sending guide is ticket 57's.
- [GLOSSARY.md](../../../GLOSSARY.md) only if "Large message" disagrees with the code.

## Devices needed

None. Host and simulator only. The 128 KiB device pairs run in ticket 62.
