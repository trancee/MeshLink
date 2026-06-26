# Pair 13 — xcover_mi_note3

## Introduction

Pair 13 (xcover_mi_note3) is a failed initial run over SM-G390F → Mi Note 3. The sender started GATT transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 114 · final sender ignored 0 · final passive ignored 82

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: SM-G390F (42004386e43c8589)
- Passive: Mi Note 3 (42c2cf)
- Sender API level: 28
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `GATT`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/13_xcover_mi_note3_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.1s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/13_xcover_mi_note3_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/13_xcover_mi_note3_final`
- Target peer id: iGXnUuQrytCe7yERZJloaIUIWWhymgZSupNp+Xiu3Q8=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 30.9s
- Final status: failed (capture) in 12.7s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Route stage: discovery-stalled
- Route evidence: 06-26 10:09:10.446 22541 22573 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup-state=guided.viewModel.discovery.stalled role=PASSIVE count=0 selectedPeerId=none elapsedSeconds=3.0 initAt=1782482947432

## Transport evidence

- Sender transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:09:01.109  8688  8688 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=iGXnUuQrytCe7yERZJloaIUIWWhymgZSupNp+Xiu3Q8= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 10:08:47.791 22178 22178 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 1.0s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as SM-G390F
    participant Passive as Mi Note 3
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (1.0s)
    Passive-->>Matrix: transport start recorded (1.0s)
    Matrix->>Matrix: wait for passive peer id (0.1s)
    Sender->>Passive: discovery and route establishment (12.8s)
    Sender->>Passive: send guided payload (12.8s)
    Matrix-->>Matrix: failure summary recorded in report (12.8s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>1.0s]
    B --> C[Wait for passive peer id<br/>0.1s]
    C --> D[Discovery and route establishment<br/>12.8s]
    D --> E[Send guided payload<br/>12.8s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 16:09:01.109  8688  8688 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=iGXnUuQrytCe7yERZJloaIUIWWhymgZSupNp+Xiu3Q8= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 10:08:47.791 22178 22178 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 10:09:10.446 22541 22573 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup-state=guided.viewModel.discovery.stalled role=PASSIVE count=0 selectedPeerId=none elapsedSeconds=3.0 initAt=1782482947432
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
    "line": "06-26 10:08:47.791 22178 22178 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 1.5,
    "line": "06-26 10:08:49.187 22178 22178 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 8.0,
    "line": "06-26 16:09:01.109  8688  8688 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_mi_note3 storage=13_xcover_mi_note3_initial targetPeerId=iGXnUuQrytCe7yERZJloaIUIWWhymgZSupNp+Xiu3Q8= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 30.9
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
