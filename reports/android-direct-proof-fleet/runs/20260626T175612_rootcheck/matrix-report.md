# Android direct-proof matrix

## Overview

| Metric | Value |
|---|---|
| Completed pairs | 1 |
| Passing pairs | 0 |
| Failing pairs | 1 |
| Pending pairs | 0 |
| Fail-fast | enabled |
| Max failures | — |
| Stopped early | yes |
| Stop reason | pair a065_nam_lx9 failed during capture |

Foreign scan summary: sender ignored 0 · passive ignored 65
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
        Matrix->>Fleet: capture inventory (1 pairs)
        Matrix->>Sweep: prepare directed sweep (1 completed)
        Fleet-->>Matrix: inventory ready (0 passing · 1 failing)
        note over Fleet,Sweep: failure bucket so far = capture/route stall
    end
    rect rgba(236, 253, 245, 0.55)
        Sweep->>Sweep: execute pair lane across 1 completed pairs
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
| A065 | capture/route stall | 1 |


## Run setup

- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T175612_rootcheck/fleet.md`
- Fleet JSON: `reports/android-direct-proof-fleet/runs/20260626T175612_rootcheck/fleet.json`
- `foreignScanSummary` is written to both `fleet.json` and `progress.json` for downstream tooling.
- Fail-fast: enabled
- Stopped early: yes
- Stop reason: pair a065_nam_lx9 failed during capture
