# Prove the link layer on real phone pairs

Status: ready-for-human
Type: HITL
Blocked by: 30, 31, 32

## Behavior

The M4 exit criterion holds on real phones: links rotate, fragment, flow-control, rekey and upgrade to L2CAP CoC where both phones support it. The runs also confirm the `CREDIT` window of ticket 29 and the proximity buckets of ticket 32. The agent prepares the scenarios. The owner runs them in the home lab, and each run becomes a T9 evidence record.

New lab scenarios, each with a timeout:

- **`link-rotation`:** a gated `Classical` pair stays linked across at least 3 own rotations and one resolving-key update; neither side ever shows a second peer. An open-mesh run shows recognition only after a new handshake.
- **`link-fragments`:** bursts of multi-record direct messages both ways, up to the largest size the API accepts before ticket 50, on GATT and on CoC. Every message arrives once, intact, and any `BUSY` is followed by a retry.
- **`link-rekey`:** a secured link held for more than 3,600 s with steady traffic shows a rekey in each direction and no drop.
- **`link-coc`:** the link switches to CoC on pairs where both support it, and stays on GATT otherwise. A forced CoC close ends the link, and a fresh GATT link follows.
- **`link-phy`:** the trace shows the 1M|2M mask and a 2M request on 2M phones, and no PHY command on `p10lite`.
- **`link-credit`:** sustained GATT notification bursts from the peripheral with the `gatt-credit-window` of 8 records (ticket 29). The peripheral never exceeds its credits, no notification is dropped, and the link stays up past 60 s after the burst, on the two pairs that failed in the bake-off: `ip15`↔`p10lite` and `se2016`↔`pixel6`.
- **`link-proximity`:** the phones stand at about 0.3 m, 2 m and 8 m in turn; each side records `Peer.proximity` (smoothed RSSI and bucket) over time. The buckets should read `Immediate`, `Near` and `Far`, and `Unknown` 30 s after one phone stops.

Pairs, one per platform pairing plus the oldest Android:

| Pair | Covers |
|---|---|
| `pixel6` + `nothing2` | Android↔Android, CoC, 2M |
| `pixel6` + `ip15` | Android↔iOS, CoC |
| `ip15` + `ip12mini` | iOS↔iOS, CoC |
| `p10lite` + `pixel6` | Android 8: GATT only, 1M, no PHY commands |
| `ip15` + `p10lite` | `link-credit`: bake-off failure pair |
| `se2016` + `pixel6` | `link-credit`: bake-off failure pair |

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 1–9 and [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 3–6.
- [link.md: Fragments](../../../docs/spec/link.md#fragments), [Busy reply](../../../docs/spec/link.md#busy-reply), [Rekey](../../../docs/spec/link.md#rekey), [Bearer switch](../../../docs/spec/link.md#bearer-switch), [Resolving key update](../../../docs/spec/link.md#resolving-key-update).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 7, 8 and 13, and the 2026-11-13 [amendment](../../../docs/adr/0017-testing-architecture.md#amendments).
- [Bearer bake-off library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md), the baseline for CoC and PHY behavior; R14 is the flow-control evidence behind the `CREDIT` frame.
- [Ticket 29](29-link-limits-flow-control.md) (`CREDIT` frame, `gatt-credit-window` = 8) and [ticket 32](32-phy-advertising-scanning.md) (proximity EWMA and buckets, recorded in an ADR 0007 amendment).
- [Lessons, Device lab](../../../docs/agents/lessons.md#device-lab).

## Acceptance criteria

- [ ] AFK: `tools/lab/` and the lab app gain the seven scenarios above, each with a timeout and a pass rule.
- [ ] Owner: every scenario passes on every pair in the table where it applies; `link-coc` expects GATT on the `p10lite` pair.
- [ ] Owner: the radio trace of each CoC run shows exactly one `BEARER_SWITCH` per direction, and the `p10lite` run shows no PHY command.
- [ ] Owner: flow control holds during `link-fragments` on the Android peripheral: no `already congested` drop, and no iOS close about 30 s after a burst (rule R14).
- [ ] Owner: `link-credit` passes on `ip15`↔`p10lite` and `se2016`↔`pixel6`. If the window of 8 fails, the agent changes `gatt-credit-window` through a spec change (D-xx entry, regenerated vectors) and the ADR 0009 amendment of ticket 29, and the run repeats.
- [ ] Owner: `link-proximity` runs on `pixel6`↔`ip15` and `pixel6`↔`nothing2`. If the buckets disagree with the distances, the agent adjusts the thresholds through the ADR 0007 amendment of ticket 32, with the evidence record cited, and the run repeats.
- [ ] `python3 tools/lab/lab.py evidence --run <dir>` writes one T9 record per run under `docs/evidence/`, with throughput, connect time and rekey times per pair, and environment metadata. Only aliases appear, and `python3 tools/check_private.py` passes.
- [ ] A failure is fixed in its owning ticket's code with a regression test, then the run repeats. No retry hides it (ADR 0017 decision 13).

## Proof commands

Profile names are the ones ticket 16 defines.

```sh
python3 tools/lab/lab.py run --scenario link-rotation --devices pixel6,ip15 --profile classical
python3 tools/lab/lab.py run --scenario link-fragments --devices pixel6,nothing2 --profile classical
python3 tools/lab/lab.py run --scenario link-rekey --devices ip15,ip12mini --profile classical
python3 tools/lab/lab.py run --scenario link-coc --devices pixel6,ip15 --profile classical
python3 tools/lab/lab.py run --scenario link-phy --devices p10lite,pixel6 --profile classical
python3 tools/lab/lab.py run --scenario link-credit --devices ip15,p10lite --profile classical
python3 tools/lab/lab.py run --scenario link-credit --devices se2016,pixel6 --profile classical
python3 tools/lab/lab.py run --scenario link-proximity --devices pixel6,ip15 --profile classical
python3 tools/lab/lab.py run --scenario link-proximity --devices pixel6,nothing2 --profile classical
python3 tools/lab/lab.py evidence --run <dir>
python3 tools/check_private.py
```

## Docs to update

- `docs/evidence/<date>-link-layer/` (new, generated by `lab.py evidence`).
- `docs/agents/lessons.md`: any new device-lab fact found during the runs.
- Only if a run forces a change: the ADR 0009 amendment and `limits.json` (credit window), or the ADR 0007 amendment (proximity thresholds).

## Devices needed

- Android: `pixel6`, `nothing2`, `p10lite`, through the remote adb server.
- iOS: `ip15`, `ip12mini`, through `devicectl`; `se2016` (iOS 15) through libimobiledevice with the team B bundle.
