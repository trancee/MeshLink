# How do open and unsecured meshes resist fake identities (Sybil)?

Type: prototype
Status: resolved

## Question

In the routing simulation, 30% fake identities split the mesh under any neighbor selection strategy. Evaluate defenses (identity cost such as proof of work, peer scoring, neighbor diversity) in the simulator, and decide what v1 ships and what it documents as a limitation.

## Context

- Throwaway branch from `prototype/routing-sim`; pre-registered criteria.
- Gated meshes already cost a stolen mesh key (profile guarantees).

## Pre-registration (agreed 2026-11-08, before any run)

Branch: throwaway `prototype/sybil` from `prototype/routing-sim` (`ca9a393`). All results simulated.

**Configuration under attack:** the v1 configuration (N1+N2 with bridge rule, density-adaptive routing and broadcast, catch-up), medium density, 200 phones, Android, static and walking, 10 seeds.

**Attackers** (fakes never close a link and dial every honest phone they hear; +10 dB):

- Dumb (stage A model): empty neighbor list, forwards nothing; 10% and 30% of nodes, 16 links each.
- Smart: true neighbor list; forwards broadcasts, route requests and replies like an honest phone, but drops unicast data and acks (black hole); 10% and 30%.
- Few radios: 3 attacker radios × 20 links, dumb and smart.

Identities are free: an evicted fake may come back under a new identity, so no defense may rely on blocking an identity.

**Defenses** (alone and combined; D1–D3 share one usefulness counter):

- Usefulness counter: per neighbor, a decaying count of timely deliveries (broadcast data or IHAVE, route request, route reply or ack for an ID not yet seen or first seen less than 1 s ago).
- D1 scoring: periodically evict the least useful link once it is past a grace period.
- D2 probation: at most 2 inbound links that have not yet earned credits.
- D3 confirmed neighbor lists: diversity uses only list entries that other neighbors confirm; a neighbor with no confirmed entries counts as most redundant.
- D4 identity cost (proof of work): analyzed on paper, not simulated.

**Pass criteria:** a defense ships if, at 30% fakes, honest phones keep ≥ 0.8 of themselves in the largest connected group (now ≤ 0.05), and, without fakes, delivery, join time and partition repair stay within one 95% interval of the v1 baseline and control traffic stays ≤ 10% of link capacity. Smart-attacker delivery is reported, not gated.

**If nothing passes:** v1 ships without a routing defense and documents the limit; gated meshes are the availability answer.

## Answer

**v1 ships no routing-layer Sybil defense; gated meshes are the availability answer** (pre-registered fallback, accepted 2026-11-08). Evidence: `docs/evidence/2026-11-sybil/README.md` (simulated, 1,200 runs, branch `prototype/sybil`).

- At 30% fakes the honest largest group stayed at 0.02–0.03 in all 8 arms (baseline, D1, D2, D3 and every combination), dumb and smart, static and walking; criterion 0.8. A free identity refills every slot a defense frees.
- No-fakes cost was within the baseline interval and control traffic ≤ 5% of link capacity, so cost was not the blocker.
- D2 hurt honest newcomers (dumb 10%: 0.90 → 0.82); D3 helped slightly at 10% (0.92); D1 never fired once fakes dominated and cannot see smart fakes.
- Smart fakes (relay floods, black-hole unicast): broadcast 1.00, unicast 0.77 / 0.74 at 10%.
- Realistic budget (3 radios × 20 links): LCC 0.99, unicast 0.98–0.99. The attacker pays in radios, not identities.
- D4 proof of work rejected on paper (a laptop out-computes a phone; honest phones pay battery; it only slows re-entry).

Docs updated: threat model (Availability), ADR 0008 v7 amendment and risk, security profile guarantees, routing-simulation evidence, lessons.
