# Pair 01 — a065_nam_lx9

## Introduction

Pair 01 (a065_nam_lx9) is a failed initial run over A065 → NAM-LX9. The sender started unknown transport, the passive side started unknown transport, and the pair stalled at launch before route establishment.

## Setup

- Sender: A065 (1f1dad34)
- Passive: NAM-LX9 (2ASVB21B09005117)
- Sender API level: 36
- Passive API level: 31
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `unknown`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260621T153830/01_a065_nam_lx9_report.md`
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260621T153830/fleet.md`
- Peer lookup time: 0.1s
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260621T153830/01_a065_nam_lx9_initial`
- Final run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260621T153830/01_a065_nam_lx9_final`
- Target peer id: Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk=

## Result

- Initial status: failed (launch) in 57.2s
- Final status: failed (launch) in 40.8s
- Initial failure reason: Android passive transport did not start within 20.0 seconds
- Final failure reason: Android passive transport did not start within 20.0 seconds
- Route stage: unknown
- Route evidence: —

## Transport evidence

- Sender transport mode: `unknown`
  - `start()`
  - Startup marker: `—`
  - Elapsed: —
- Passive transport mode: `unknown`
  - `start()`
  - Startup marker: `—`
  - Elapsed: —
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as A065
    participant Passive as NAM-LX9
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (—)
    Passive-->>Matrix: transport start recorded (—)
    Matrix->>Matrix: wait for passive peer id (0.1s)
    Sender->>Passive: discovery and route establishment (40.8s)
    Sender->>Passive: send guided payload (40.8s)
    Matrix-->>Matrix: failure summary recorded in report (40.8s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>—]
    B --> C[Wait for passive peer id<br/>0.1s]
    C --> D[Discovery and route establishment<br/>40.8s]
    D --> E[Send guided payload<br/>40.8s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `—`
- Passive startup marker: `—`
- Route evidence: —
- Passive route evidence: —

| Initial artifact | Path | Captured |
|---|---|---|
| Initial senderLogcat | `sender_logcat.log` | yes |
| Initial passiveLogcat | `passive_logcat.log` | yes |
| Initial senderStart | `sender_start.txt` | yes |
| Initial passiveStart | `passive_start.txt` | yes |
| Initial androidHistory | `android_history.json` | no |
| Initial androidExport | `android_export.json` | no |

## Startup timing

```json
{
  "launch": {
    "passiveStartupWaitSeconds": 20.0,
    "passiveTransportWaitSeconds": 20.0,
    "postResultIdleSeconds": 2.0
  },
  "passive": {
    "elapsedSeconds": 20.0,
    "line": null,
    "observed": false
  },
  "passiveTransport": {
    "elapsedSeconds": 20.0,
    "line": null,
    "observed": false
  },
  "sender": {
    "elapsedSeconds": null,
    "line": null,
    "observed": false
  },
  "totalSeconds": 57.2
}
```

## Captured evidence map

```json
{
  "final": {
    "androidExport": false,
    "androidHistory": false,
    "passiveLogcat": true,
    "passiveStart": true,
    "senderLogcat": true,
    "senderStart": true
  },
  "initial": {
    "androidExport": false,
    "androidHistory": false,
    "passiveLogcat": true,
    "passiveStart": true,
    "senderLogcat": true,
    "senderStart": true
  }
}
```

## Evidence files

- sender_logcat.log
- passive_logcat.log
- summary.json
