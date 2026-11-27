# PROTOTYPE, throwaway: per-neighbor rate limits on relayed floods (ticket 74)

This code is never merged. Only the decision moves to `main`.

## Question

Should relays drop flood frames that go over a budget per incoming link? For signed broadcasts, which keying works best:

- per link only;
- per origin only;
- per origin plus a per-link total.

Which rate and burst values cost honest traffic nothing?

## Setup

- `NeighborRatePrototype` (`meshlink-testing`, androidHostTest), with hooks through `RateProto` in `Floods`, `Router`, `RouteDiscovery` and `Lookups`.
- Seeded `VirtualAir`: 30 nodes at 10 kB/s per link, low (6 peers) and medium (25 peers) density, seeds 1–3.
- Preset `EmergencyBroadcast`, profile `Unsecured`.
- Timing: 90 s settle, 180 s of traffic, 120 s drain.
- Honest traffic: one 200 B unicast per second between random members, and one 200 B broadcast every 20 s.
- Attacker: one member.

Scenarios:

| Scenario | What happens |
|---|---|
| calm | Honest traffic only. |
| burst | Every member broadcasts twice at random times within 10 s. |
| flood-bc | The attacker broadcasts 900 B at 20/s under one identity. |
| flood-rot | The same flood, with a fresh origin key on every frame (Sybil). |
| flood-uni | The attacker sends 200 B unicasts at 20/s to random unknown NodeIds. In low density these flood with hop limit 255; in medium density they trigger route requests. |

Limiters:

| Limiter | Broadcasts | Unicast, route requests |
|---|---|---|
| none | — | — |
| link | 2/s, burst 10 per link | 10/40, 5/20 per link |
| link4 | 4/s, burst 20 per link | 10/40, 5/20 |
| origin | 1/s, burst 5 per origin | 10/40, 5/20 |
| originlink | 1/5 per origin, plus a 4/20 total per link | 10/40, 5/20 |
| uni5 | as link | 5/20, 5/20 |

## Results

Each cell is the worst of seeds 1–3. Raw lines are in `run1.txt`, `run2.txt`, `link4.txt` and `uni5.txt`.

| Cell | Scenario | Limiter | Unicast delivered | Unicast p95 s | Honest bc coverage | Honest bc p95 s | Attack coverage |
|---|---|---|---|---|---|---|---|
| low | burst | none | 1.000 | 5.1 | 1.000 | 0.6 | – |
| low | burst | link | 1.000 | 5.1 | 0.976 | 0.6 | – |
| low | burst | link4, origin, originlink | 1.000 | 5.1 | 1.000 | 0.6 | – |
| low | flood-bc | none | 1.000 | 30.5 | 1.000 | 19.7 | 0.30–0.32 |
| low | flood-bc | link | 1.000 | 2.1 | 0.850 | 0.7 | 0.13–0.21 |
| low | flood-bc | link4 | 1.000 | 16.2 | 0.955 | 6.9 | 0.27–0.32 |
| low | flood-bc | origin | 1.000 | 15.0 | 1.000 | 0.2 | 0.08–0.16 |
| low | flood-bc | originlink | 1.000 | 15.1 | 1.000 | 0.2 | 0.08–0.16 |
| low | flood-rot | origin | 1.000 | 30.5 | 1.000 | 19.7 | 0.30–0.32 |
| low | flood-rot | originlink | 1.000 | 16.2 | 0.955 | 6.9 | 0.27–0.32 |
| low | flood-uni | none | 0.598 | 134.7 | 0.695 | 146.1 | – |
| low | flood-uni | link (uni 10/40) | 0.989 | 36.9 | 1.000 | 0.9 | – |
| low | flood-uni | uni5 | 1.000 | 15.1 | 1.000 | 0.2 | – |
| medium | burst | every limiter | 1.000 | 1.3 | 1.000 | 1.1 | – |
| medium | flood-bc | none | 1.000 | 29.9 | 0.996 | 14.8 | 0.31–0.32 |
| medium | flood-bc | link | 1.000 | 1.4 | 1.000 | 2.0 | 0.14–0.19 |
| medium | flood-bc | link4 | 1.000 | 4.8 | 1.000 | 6.5 | 0.20–0.28 |
| medium | flood-bc | origin, originlink | 1.000 | 1.4 | 1.000 | 0.8 | 0.07–0.08 |
| medium | flood-rot | origin | 1.000 | 29.9 | 0.996 | 14.8 | 0.31–0.32 |
| medium | flood-rot | originlink | 1.000 | 4.8 | 1.000 | 6.5 | 0.20–0.28 |
| medium | flood-uni | none | 0.978 | 74.6 | 0.991 | 8.7 | – |
| medium | flood-uni | link (uni 10/40) | 1.000 | 1.4 | 1.000 | 0.4 | – |
| medium | flood-uni | uni5 | 1.000 | 2.1 | 0.996 | 0.2 | – |

In the calm scenario every limiter matches none and drops nothing.

## Findings

1. **Per origin alone fails against Sybil keys.** With a rotating key, flood-rot under the origin limiter is identical to no limiter.
2. **Per link at 2/10 stops the most attack traffic, but honest broadcasts pay.** They share the link budget with the attacker: low-density coverage falls to 0.850 under attack and to 0.976 in an honest burst.
3. **Per origin plus a per-link total of 4/20 is never worse than per link at 4/20.**
   - Against one identity, it matches per origin, with honest coverage 1.000.
   - Against rotating keys, it falls back to the 4/20 total.
   - It costs the honest burst nothing.
4. **The unicast and request limits matter most.** Without them, a unicast flood cuts low-density delivery to 0.598, with a p95 of 135 s. Unicast at 5/20 brings the low-density p95 to 15 s, against 37 s at 10/40. Medium density is fine at 10/40.

## Limitations

- The harness uses `Unsecured`, so the per-origin key is the claimed sender, which can be forged. The origin variants model the secured profiles, where the origin is the signed `origin_key`.
- `LOOKUP_REQUEST` is secured-only and is not exercised.
- High density is not measured.
- 30 nodes only.

## Verdict

Pending the owner's value decision on ticket 74.
