# Pair 20 — mi_note3_e940

## Introduction

Pair 20 (mi_note3_e940) is a failed initial run over Mi Note 3 → E940-2849-00. The sender started GATT transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 52 · final sender ignored 0 · final passive ignored 21

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: Mi Note 3 (42c2cf)
- Passive: E940-2849-00 (GX6CTR500184)
- Sender API level: 28
- Passive API level: 33
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `GATT`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/20_mi_note3_e940_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/20_mi_note3_e940_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/20_mi_note3_e940_final`
- Target peer id: D8wGqHAqdbB8PQJv4G1DHca4hvC0NpQ4Zt2sWrF0cGM=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 19.7s
- Final status: failed (capture) in 4.3s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Route stage: route-pending
- Route evidence: 06-26 16:13:04.671 21783 21806 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none

## Transport evidence

- Sender transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 10:13:00.814 27225 27225 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=D8wGqHAqdbB8PQJv4G1DHca4hvC0NpQ4Zt2sWrF0cGM= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:12:54.057 21692 21692 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 0.8s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as Mi Note 3
    participant Passive as E940-2849-00
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (0.8s)
    Passive-->>Matrix: transport start recorded (0.8s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (4.3s)
    Sender->>Passive: send guided payload (4.3s)
    Matrix-->>Matrix: failure summary recorded in report (4.3s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>0.8s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>4.3s]
    D --> E[Send guided payload<br/>4.3s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 10:13:00.814 27225 27225 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=D8wGqHAqdbB8PQJv4G1DHca4hvC0NpQ4Zt2sWrF0cGM= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 16:12:54.057 21692 21692 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:13:04.671 21783 21806 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none
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
    "passiveStartupWaitSeconds": 30.0,
    "passiveTransportWaitSeconds": 30.0,
    "postResultIdleSeconds": 2.0
  },
  "passive": {
    "elapsedSeconds": 0.8,
    "line": "06-26 16:12:54.057 21692 21692 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.8,
    "line": "06-26 16:12:54.919 21692 21692 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 2.8,
    "line": "06-26 10:13:00.814 27225 27225 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_e940 storage=20_mi_note3_e940_initial targetPeerId=D8wGqHAqdbB8PQJv4G1DHca4hvC0NpQ4Zt2sWrF0cGM= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 19.7
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
