# Fake identities (Sybil) in open meshes: evidence

- **Date:** 2026-11-08.
- **Ticket:** `.scratch/meshlink-v1/issues/22-sybil-resistance.md`
- **Decisions it feeds:** [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md) (risk "Fake identities"), [threat model](../../explanation/threat-model.md#availability), [security profile guarantees](../../reference/security-profile-guarantees.md)
- **Code:** throwaway branch `prototype/sybil`, from `prototype/routing-sim` (`ca9a393`), under `prototypes/routing-sim/`: stage S runner `StageS.kt`, defenses `SybilDefense.kt`, full tables in `results/stage-s/stage-s-report.md`. It is never merged.
- **Data:** per-cell means and 95% intervals in [`agg-s.csv`](agg-s.csv).

All numbers are **simulated**, from the routing simulator with the bake-off calibration. No phone ran this attack.

## 1. Question

In the routing simulation (stage A), 30% fake identities split the honest mesh under every neighbor selection strategy. Can a routing-layer defense keep an **open** mesh (or the `unsecured` profile) connected, at an acceptable cost when there is no attacker? If not, what does v1 document?

## 2. Method

Pre-registered in the ticket before any run.

- **Configuration under attack:** the v1 routing configuration (N1+N2 neighbor selection with the bridge rule, density-adaptive unicast with K2 acks, Plumtree broadcast with catch-up), medium density, N = 200, Android only, static and walking (random waypoint), 10 seeds per cell. Identical seeds across arms.
- **Shares** count fakes inside N, as in stage A: 10% is 180 honest + 20 fakes, 30% is 140 + 60.
- **Attackers.** Every fake gets +10 dB on its links, holds up to 16 links, never closes a link and dials every honest phone it hears (2 per scan). Identities are free: when an honest phone closes a fake's link, it forgets that identity (cool-down, failures, score), and the fake re-dials at once as someone new.
  - *Dumb* (stage A model): empty neighbor list, forwards nothing.
  - *Smart:* sends its true neighbor list; forwards broadcasts, IHAVE, grafts, catch-up, route requests and route replies like an honest phone; drops unicast data and acks (black hole).
  - *Few radios:* 200 honest phones + 3 attacker radios with 20 links each, dumb and smart. This is the realistic hardware budget: an nRF52840 holds about 20 links.
- **Defenses,** alone and in every combination (8 arms with the baseline). D1–D3 share one usefulness counter: per link, an exponentially decaying count (half-life 60 s, assumed) of timely deliveries, meaning a broadcast data message or IHAVE, route request, route reply or ack whose ID was new or first seen less than 1 s ago.
  - *D1 scoring:* at the 30 s diversity check, when the phone has at most one free slot, close the least useful link past a 60 s grace, if the median score of the other links is ≥ 2 and this link's score is < 0.25 × that median. Quiet phones never evict.
  - *D2 probation:* at most 2 inbound links with score < 1, counting dials in progress; further unproven inbound dials are refused.
  - *D3 confirmed neighbor lists:* N2 diversity counts a listed peer only if it is this phone, one of its own links or dials, listed by another of its neighbors, or heard by its own scans in the last 60 s. A neighbor with no confirmed entry is the most redundant (an empty list used to look the most diverse).
  - *D4 identity cost (proof of work):* paper analysis only (section 5).
- **Pass criteria.** A defense ships if honest phones keep ≥ 0.8 of themselves in the largest connected honest group (LCC) at dumb 30% and at smart 30%, static and walking; and, without fakes, unicast delivery, broadcast arrival, join time and partition repair stay within one 95% interval of the baseline, with control traffic ≤ 10% of link capacity. Smart-attacker delivery is reported, not gated.
- **Fallback (pre-registered):** if nothing passes, v1 ships without a routing defense, documents the limit, and gated meshes are the availability answer.
- **Regression check:** with all flags off, stage A (Sybil 10% static, 30% walking), stage D `ios3` (32 rows) and stage E `rot` (140 rows) reproduce exactly, apart from wall time.
- **Run:** 1,200 runs, about 2 h on an 8-core Mac.

## 3. Results

### Honest largest connected group (LCC), static / walking

Mean over 10 seeds; 95% interval shown where it exceeds 0.004.

| Arm | No fakes | Dumb 10% | Dumb 30% | Smart 10% | Smart 30% | 3 radios, dumb | 3 radios, smart |
|---|---|---|---|---|---|---|---|
| Baseline | .995 / .994 | .901 ± .018 / .892 ± .008 | .019 / .019 | .913 ± .015 / .907 ± .006 | .021 / .021 | .995 / .993 | .995 / .993 |
| D1 | .995 / .994 | .903 / .896 | .019 / .020 | .913 / .907 | .021 / .021 | .995 / .993 | .995 / .993 |
| D2 | .995 / .994 | .822 ± .022 / .804 ± .009 | .025 / .022 | .909 / .906 | .021 / .021 | .994 / .992 | .995 / .993 |
| D3 | .995 / .994 | .922 / .919 | .026 / .024 | .910 / .907 | .021 / .021 | .995 / .993 | .995 / .993 |
| D1+D2 | .995 / .994 | .840 / .809 | .024 / .022 | .914 / .906 | .021 / .021 | .994 / .992 | .995 / .993 |
| D1+D3 | .995 / .994 | .927 / .919 | .026 / .025 | .910 / .907 | .021 / .021 | .995 / .993 | .995 / .993 |
| D2+D3 | .995 / .994 | .859 / .857 | .028 / .027 | .910 / .904 | .022 / .021 | .994 / .993 | .995 / .993 |
| D1+D2+D3 | .995 / .994 | .867 / .853 | .030 / .027 | .910 / .904 | .022 / .021 | .994 / .993 | .995 / .993 |

### Baseline in detail, static / walking

| Attack | Slots held by fakes | Unicast delivery | Broadcast arrival | Join time (s) | Control (share of link capacity) |
|---|---|---|---|---|---|
| None | 0 | .998 / .997 | 1.000 / 1.000 | 2.5 / 2.6 | 0.14% / 0.18% |
| Dumb 10% | .39 / .40 | .987 / .992 | .996 / .996 | 3.4 / 3.6 | 0.16% / 0.23% |
| Dumb 30% | .98 / .98 | .023 / .011 | .004 / .005 | 108 / 92 | 0.06% / 0.07% |
| Smart 10% | .39 / .39 | .773 / .739 | 1.000 / .996 | 3.5 / 3.5 | 2.2% / 3.1% |
| Smart 30% | .97 / .97 | .018 / .010 | 1.000 / 1.000 | 96 / 81 | 4.5% / 5.0% |
| 3 radios, dumb | .067 / .069 | .997 / .999 | 1.000 / 1.000 | 2.7 / 2.7 | 0.14% / 0.19% |
| 3 radios, smart | .065 / .067 | .989 / .984 | 1.000 / 1.000 | 2.6 / 2.8 | 0.30% / 0.52% |

Delivery and arrival are over all honest pairs and phones. Join time is the median time to a phone's first honest link. Partition repair (no fakes, partition-merge cell) is 7–9 ± 2 s in every arm.

### Verdict per criterion

- **LCC at 30%:** every arm fails, dumb and smart, static and walking (0.019–0.030 against 0.8).
- **Cost without fakes:** within the baseline interval for every arm, except two small differences flagged only because the baseline interval is near zero: D3 static broadcast arrival 0.997 (baseline 1.000 ± 0.000), D1+D2 static join time 2.6 s (2.5 ± 0.1 s).
- **Control traffic:** at most 5.0% of link capacity (smart 30%, walking); passes.

No arm passes, so the pre-registered fallback applies.

## 4. Interpretation

- **Free identities refill freed slots.** Any slot a defense frees is re-dialed at once by a fake under a new identity, and the fakes' stronger signal makes honest phones' own dials prefer them too. At 30% the fakes hold 95–98% of honest slots in every arm.
- **D1 cannot act once fakes dominate.** When most of a phone's links have score zero, the median rule never fires. Smart fakes earn credit by relaying floods, so D1 results equal the baseline in the smart cells.
- **D2 hurts honest newcomers.** It refuses about 2 honest dials per phone per minute under attack, lowering dumb 10% LCC from 0.90 to 0.82 (0.80 walking).
- **D3 removes the empty-list advantage** and gains a little at 10% (0.90 → 0.92), nothing at 30%.
- **The smart attacker splits the two services.** Broadcast stays at 1.00; unicast falls to 0.77 / 0.74 at 10% and about 0.01–0.02 at 30%. Peer scoring by relay usefulness cannot see a black hole that relays floods.
- **A realistic radio budget barely matters.** 3 radios × 20 links hold about 6.5% of honest slots; LCC stays 0.99 and unicast 0.98–0.99 even with black holes. Splitting a 200-phone mesh needs on the order of 60 attacker radios with 16 links each in range of the honest phones. In BLE the attacker pays in radios and link slots, not in identities.

## 5. D4: identity cost (paper analysis)

Proof of work (a costly puzzle per identity) is rejected for v1:

- The attack above never needs many identities at once: it needs one identity per link slot, re-created only after an eviction. The puzzle cost only slows re-entry after D1 evicts, and D1 does not evict in the collapse regime.
- If the puzzle is bound to the on-air identifier, honest phones pay it on every rotation (every 5 min, ADR 0010), in battery; if it is bound to a long-term key, relays cannot check it without linking rotations.
- A laptop or server computes puzzles orders of magnitude faster than a phone, so any difficulty a phone tolerates is cheap for an attacker.
- The binding cost is radios and airtime, which a puzzle does not change.

## 6. Decision

v1 ships **no routing-layer Sybil defense** (none of D1–D4). Open meshes and the `unsecured` profile document that a determined attacker with many radios can split the mesh and black-hole unicast; apps that need availability against such an attacker use a **gated mesh**, where every link needs the mesh key. The guarantee stays "bounded resources, no delivery guarantee".

## 7. Limitations

- Simulated only, one density (medium), N = 200, Android only; the attacker model is fixed and generous (+10 dB, never closes links, re-dials at once).
- The configuration used 16 B message IDs and no identifier rotation (v1: 12 B, 5 min); not expected to matter for this attack.
- Parameter values for D1–D3 (half-life, grace, thresholds, confirmation window) are assumed, not swept. A different tuning could change the 10% results; the 30% collapse comes from slot refill, which no tuning of these rules removes.
- A phone's own originated message IDs were not marked seen, so echoes could earn credit; this favors the defenses, not the attacker.
- Gated meshes were not simulated: a stolen mesh key gives the same attack inside a gated mesh.
