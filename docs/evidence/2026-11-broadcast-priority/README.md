# Broadcast priority under honest load and under a broadcast flood

- **Date:** 2026-11-25
- **Question:** `EmergencyBroadcast` turns on `broadcastPriority`. Should a per-link budget (rate R per second, burst B) limit which relayed broadcasts get priority, so that one member flooding broadcasts can't use it to starve unicast? If so, which R and B?
- **Answer:** no budget. The flood harms delivery the same with priority on or off, and no budget for priority changed that. Only dropping relayed broadcasts over a per-link budget restored delivery, at a cost to honest broadcasts. The owner chose to ship priority with only its bound of three overtakes, and to move the drop-based admission question to ticket 74 ([ADR 0011](../../adr/0011-modules-toggles-and-presets.md#amendments) amendment "presets and toggles built").
- **Kind:** simulated only, on `VirtualAir`. Nothing here was measured on phones.
- **Source:** throwaway branch [`proto/broadcast-flood`](https://github.com/trancee/MeshLink/tree/proto/broadcast-flood), commit `723e83a`, never merged. Its `BroadcastFloodPrototype` harness and `FloodPrototype` hook produced the rows in [`raw/`](raw/).

## Method

- 30 nodes placed at random in a square sized for a mean of 6 peers in range (low density) or 25 (medium), seeds 1 to 3. Only the largest connected group counts: 16 to 30 members.
- `Unsecured`, `EmergencyBroadcast` preset, `VirtualAir` at 10,000 bytes per second per link direction.
- 90 s to settle, 180 s of traffic, 120 s to drain.
- Honest traffic: one member broadcasts 200 B every 20 s; a random member sends 200 B to another every second.
- Flood: one member broadcasts 900 B 20 times per second.
- Busy: no flood, but the unicasts are 4,000 B every 250 ms, more than the low-density cells can carry.
- Burst: no flood and no regular broadcaster; every member broadcasts 200 B twice at random times within the first 10 s.
- Metrics: unicast p95 is the 95th percentile of send-to-delivery time over delivered unicasts. Coverage is the fraction of (broadcast, other member) pairs delivered, and broadcast p95 its delivery time.
- Variants: `fifo` is priority off; `prio` is priority on with the bound of 3; `R`/`B` add a per-incoming-link token bucket. In run 1 a broadcast over the budget lost priority and became a non-overtakable frame; from run 2 it lost priority and queued like a unicast. `drop` discards relayed broadcasts over the budget instead.

## Results

### Without a flood, priority changes nothing

Little queues, so both orders write nearly the same frames. Unicast p95 was 0.1 to 6.1 s and honest broadcast p95 0.1 to 0.7 s. Priority on and off gave identical rows in five cells and differed by at most 23 ms in the sixth (medium, seed 1).

### Under honest overload, priority helps broadcasts

Busy variant, priority off → on:

| Cell | Seed | Broadcast coverage | Broadcast p95 (s) | Unicasts delivered |
|---|---|---|---|---|
| low | 1 | 0.804 → 0.957 | 67.3 → 73.4 | 473 → 515 of 719 |
| low | 2 | 0.495 → 0.850 | 129.5 → 35.5 | 382 → 362 of 719 |
| low | 3 | 0.375 → 0.533 | 61.8 → 20.0 | 435 → 471 of 719 |
| medium | 1 | 1.000 → 1.000 | 1.1 → 0.7 | 719 → 719 of 719 |
| medium | 2 | 1.000 → 1.000 | 2.3 → 2.8 | 719 → 719 of 719 |
| medium | 3 | 1.000 → 1.000 | 1.6 → 1.4 | 719 → 719 of 719 |

### A flood harms delivery the same with priority on or off

p95 in seconds, unicast / honest broadcast. Honest coverage stayed at 0.973 or above. The bucket column spans R/B 0.5/3, 1/5, 2/5 and 5/10 (run 2).

| Cell | Seed | No flood | Flood, priority off | Flood, priority on | Flood, priority on with bucket |
|---|---|---|---|---|---|
| low | 1 | 1.3 / 0.1 | 23.2 / 12.2 | 31.5 / 6.8 | 16.0–35.8 / 8.5–12.2 |
| low | 2 | 6.1 / 0.1 | 43.2 / 16.6 | 30.1 / 11.6 | 30.1–59.3 / 10.8–17.7 |
| low | 3 | 0.1 / 0.1 | 45.3 / 5.0 | 29.2 / 2.7 | 24.7–30.7 / 5.1–8.9 |
| medium | 1 | 1.3 / 0.2 | 17.2 / 7.7 | 22.3 / 15.0 | 12.1–19.6 / 8.7–16.6 |
| medium | 2 | 1.2 / 0.3 | 39.4 / 15.2 | 26.3 / 12.7 | 21.3–41.5 / 9.6–12.3 |
| medium | 3 | 1.3 / 0.7 | 23.6 / 14.0 | 44.2 / 27.9 | 16.1–27.0 / 11.3–15.7 |

With priority on, unicast p95 was higher than with it off in three cells and lower in three, so priority neither cures nor consistently worsens a flood. The likely cause, consistent with these rows but not instrumented: the flooding member keeps the 32 relayed slots of each lane full, so honest frames are dropped at the full lane before any ordering applies, and arrive late through Plumtree's repair and catch-up.

### Dropping over a per-link budget restores delivery, at a cost

Run 3, unicast p95 (s) / honest broadcast coverage / honest broadcast p95 (s) / flood coverage:

| Cell | Seed | Flood, priority off | Drop R1 B5 | Drop R2 B5 | Drop R2 B10 | Priority off, drop R2 B5 |
|---|---|---|---|---|---|---|
| low | 1 | 23.2 / 1.000 / 12.2 / 0.99 | 1.5 / 0.984 / 0.2 / 0.25 | 1.3 / 0.989 / 0.7 / 0.50 | 1.6 / 0.995 / 0.7 / 0.41 | 2.1 / 0.995 / 0.7 / 0.39 |
| low | 2 | 43.2 / 1.000 / 16.6 / 1.00 | 1.6 / 0.835 / 31.6 / 0.40 | 14.1 / 0.895 / 0.4 / 0.52 | 2.2 / 0.900 / 0.7 / 0.53 | 14.1 / 0.930 / 0.7 / 0.53 |
| low | 3 | 45.3 / 1.000 / 5.0 / 1.00 | 0.2 / 1.000 / 0.1 / 0.54 | 0.2 / 1.000 / 0.2 / 0.72 | 6.1 / 1.000 / 0.1 / 0.76 | 0.5 / 1.000 / 0.1 / 0.76 |
| medium | 1 | 17.2 / 1.000 / 7.7 / 0.86 | 1.3 / 1.000 / 1.2 / 0.23 | 0.3 / 1.000 / 0.9 / 0.50 | 1.3 / 1.000 / 1.5 / 0.37 | 1.3 / 1.000 / 0.9 / 0.50 |
| medium | 2 | 39.4 / 1.000 / 15.2 / 0.97 | 1.4 / 0.987 / 0.8 / 0.24 | 1.3 / 1.000 / 1.1 / 0.46 | 1.4 / 1.000 / 1.8 / 0.65 | 1.3 / 1.000 / 1.2 / 0.57 |
| medium | 3 | 23.6 / 1.000 / 14.0 / 1.00 | 1.3 / 1.000 / 0.7 / 0.26 | 1.4 / 1.000 / 1.2 / 0.39 | 1.4 / 1.000 / 0.9 / 0.42 | 1.3 / 1.000 / 1.1 / 0.47 |

- The benefit comes from the drop, not from priority: priority off with the same drop does as well.
- The budget is per link, not per origin, so honest broadcasts relayed over the flooded link share it. In low density seed 2 their coverage fell to 0.835 to 0.930.
- With drop R1 B5 and the honest burst, coverage was 0.989, 0.908 and 0.996 at low density with p95 of 78.8, 47.7 and 0.1 s, and 0.986 to 1.000 at medium density. No burst ran without the drop, so this cost is not separated from the burst itself.

## Limits

- Three seeds per cell, one network size, one airtime rate and one attacker; no high-density cell.
- `VirtualAir` models airtime per link direction, not shared channel contention.
- The attacker is a member running unmodified MeshLink; a modified node could also forge `message_id`s or ignore the hop limit.
