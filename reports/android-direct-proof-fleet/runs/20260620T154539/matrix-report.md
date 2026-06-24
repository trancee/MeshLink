# Android direct-proof matrix

## Overview

| Metric | Value |
|---|---|
| Completed pairs | 6 |
| Passing pairs | 0 |
| Failing pairs | 6 |
| Pending pairs | 24 |
| Fail-fast | disabled |
| Max failures | — |
| Stopped early | yes |
| Stop reason | failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing |

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
        Matrix->>Sweep: prepare directed sweep (6 completed)
        Fleet-->>Matrix: inventory ready (0 passing · 6 failing)
        note over Fleet,Sweep: failure bucket so far = capture/route stall
    end
    rect rgba(236, 253, 245, 0.55)
        Sweep->>Sweep: execute pair lane across 6 completed pairs
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
| A065 | capture/route stall | 4 |
| NAM-LX9 | launch timeout | 1 |


## Run setup

- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260620T154539/fleet.md`
- Fleet JSON: `reports/android-direct-proof-fleet/runs/20260620T154539/fleet.json`
- Fail-fast: disabled
- Stopped early: yes
- Stop reason: failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing
