# Pair 18 — mi_note3_xcover

## Introduction

Pair 18 (mi_note3_xcover) is a failed initial run over Mi Note 3 → SM-G390F. The sender started GATT transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 95 · final sender ignored 0 · final passive ignored 18

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: Mi Note 3 (42c2cf)
- Passive: SM-G390F (42004386e43c8589)
- Sender API level: 28
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `GATT`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/18_mi_note3_xcover_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.1s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/18_mi_note3_xcover_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/18_mi_note3_xcover_final`
- Target peer id: ABxAJOd5aySBOzRbLd5bfIKN+7Hx+XSU+yINX5wkO3Y=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 37.7s
- Final status: failed (capture) in 13.6s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof sender stalled before peer discovery; classified as a capture stall
- Route stage: route-pending
- Route evidence: 06-26 10:12:13.300 25622 25639 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=SENDER count=0 selectedPeerId=none

## Transport evidence

- Sender transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 10:11:59.513 25273 25273 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=ABxAJOd5aySBOzRbLd5bfIKN+7Hx+XSU+yINX5wkO3Y= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:11:45.666 12820 12820 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 3.1s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as Mi Note 3
    participant Passive as SM-G390F
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (3.1s)
    Passive-->>Matrix: transport start recorded (3.1s)
    Matrix->>Matrix: wait for passive peer id (0.1s)
    Sender->>Passive: discovery and route establishment (13.6s)
    Sender->>Passive: send guided payload (13.6s)
    Matrix-->>Matrix: failure summary recorded in report (13.6s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>3.1s]
    B --> C[Wait for passive peer id<br/>0.1s]
    C --> D[Discovery and route establishment<br/>13.6s]
    D --> E[Send guided payload<br/>13.6s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 10:11:59.513 25273 25273 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=ABxAJOd5aySBOzRbLd5bfIKN+7Hx+XSU+yINX5wkO3Y= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 16:11:45.666 12820 12820 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 10:12:13.300 25622 25639 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=SENDER count=0 selectedPeerId=none
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
    "elapsedSeconds": 11.0,
    "line": "06-26 16:11:45.666 12820 12820 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 4.3,
    "line": "06-26 16:11:49.937 12820 12820 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 2.8,
    "line": "06-26 10:11:59.513 25273 25273 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_xcover storage=18_mi_note3_xcover_initial targetPeerId=ABxAJOd5aySBOzRbLd5bfIKN+7Hx+XSU+yINX5wkO3Y= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 37.7
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
