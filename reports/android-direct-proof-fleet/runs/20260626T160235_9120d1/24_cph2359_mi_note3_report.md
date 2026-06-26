# Pair 24 — cph2359_mi_note3

## Introduction

Pair 24 (cph2359_mi_note3) is a failed initial run over CPH2359 → Mi Note 3. The sender started GATT transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 60 · final sender ignored 0 · final passive ignored 6

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: CPH2359 (EQUGS85LJNEIO7Z5)
- Passive: Mi Note 3 (42c2cf)
- Sender API level: 34
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `GATT`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/24_cph2359_mi_note3_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/24_cph2359_mi_note3_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/24_cph2359_mi_note3_final`
- Target peer id: bjRqD+OlzxZZd0rPMZ7PghauK/01HcltQJb1C3gK9h4=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 20.7s
- Final status: failed (capture) in 4.8s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof sender stalled before peer discovery; classified as a capture stall
- Route stage: route-pending
- Route evidence: 06-26 16:14:56.507 29941 29996 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=SENDER count=0 selectedPeerId=none

## Transport evidence

- Sender transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:14:51.563 29846 29846 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=bjRqD+OlzxZZd0rPMZ7PghauK/01HcltQJb1C3gK9h4= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 10:14:43.794 28214 28214 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 1.0s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as CPH2359
    participant Passive as Mi Note 3
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (1.0s)
    Passive-->>Matrix: transport start recorded (1.0s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (4.9s)
    Sender->>Passive: send guided payload (4.9s)
    Matrix-->>Matrix: failure summary recorded in report (4.9s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>1.0s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>4.9s]
    D --> E[Send guided payload<br/>4.9s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 16:14:51.563 29846 29846 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=bjRqD+OlzxZZd0rPMZ7PghauK/01HcltQJb1C3gK9h4= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 10:14:43.794 28214 28214 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:14:56.507 29941 29996 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=SENDER count=0 selectedPeerId=none
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
    "elapsedSeconds": 2.8,
    "line": "06-26 10:14:43.794 28214 28214 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 1.5,
    "line": "06-26 10:14:45.183 28214 28214 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.8,
    "line": "06-26 16:14:51.563 29846 29846 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_mi_note3 storage=24_cph2359_mi_note3_initial targetPeerId=bjRqD+OlzxZZd0rPMZ7PghauK/01HcltQJb1C3gK9h4= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 20.7
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
