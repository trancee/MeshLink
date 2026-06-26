# Android direct-proof matrix

## Overview

| Metric | Value |
|---|---|
| Completed pairs | 30 |
| Passing pairs | 0 |
| Failing pairs | 30 |
| Pending pairs | 0 |
| Fail-fast | disabled |
| Max failures | — |
| Stopped early | no |
| Stop reason | — |

Foreign scan summary: sender ignored 54 · passive ignored 2347
- How to read this report: sender/passive foreign-scan counts are summed across initial + final passes for the fleet overview, while pair reports show the same counts per run.

## Mermaid overview

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Fleet
    participant Sweep
    participant Stop
    note over Matrix: runRoot=reports/android-direct-proof-fleet/runs… · fleet=fleet.md
    rect rgba(30, 64, 175, 0.40)
        Matrix->>Fleet: capture inventory (30 pairs)
        Matrix->>Sweep: prepare directed sweep (30 completed)
        Fleet-->>Matrix: inventory ready (0 passing · 30 failing)
        note over Fleet,Sweep: failure bucket so far = capture/route stall
    end
    rect rgba(236, 253, 245, 0.55)
        Sweep->>Sweep: execute pair lane across 30 completed pairs
        Sweep->>Sweep: classify outcomes by failure stage
        note over Sweep: top failure bucket = capture/route stall
        alt at least one passing pair
            Sweep-->>Matrix: 0 passing pairs recorded
        else no passing pairs
            Sweep-->>Matrix: no successful pairs
        end
    end
    rect rgba(254, 242, 242, 0.55)
        alt stopped early
            Sweep->>Stop: stopped early
            note over Stop: failure summary recorded in report
        else sweep completed
            Sweep->>Stop: all processed pairs recorded
            note over Stop: failure summary recorded in report
        end
    end
```

## Passing pairs

| Sender | Passive | Result |
|---|---|---|

## Most common failure reason per device

| Device | Most common failure reason | Count |
|---|---|---|
| A065 | capture/route stall | 5 |
| NAM-LX9 | capture/route stall | 5 |
| SM-G390F | capture/route stall | 5 |
| Mi Note 3 | capture/route stall | 5 |
| CPH2359 | capture/route stall | 5 |
| E940-2849-00 | capture/route stall | 5 |


## Run setup

- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Fleet JSON: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.json`
- `foreignScanSummary` is written to both `fleet.json` and `progress.json` for downstream tooling.
- Fail-fast: disabled
- Stopped early: no
