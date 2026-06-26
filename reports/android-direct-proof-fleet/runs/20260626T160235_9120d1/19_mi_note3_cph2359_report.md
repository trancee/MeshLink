# Pair 19 — mi_note3_cph2359

## Introduction

Pair 19 (mi_note3_cph2359) is a failed initial run over Mi Note 3 → CPH2359. The sender started L2CAP transport, the passive side started L2CAP transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 0 · final sender ignored 0 · final passive ignored 0

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: Mi Note 3 (42c2cf)
- Passive: CPH2359 (EQUGS85LJNEIO7Z5)
- Sender API level: 28
- Passive API level: 34
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/19_mi_note3_cph2359_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/19_mi_note3_cph2359_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/19_mi_note3_cph2359_final`
- Target peer id: m7YDySx6jwpD4pNlTIBR9+n0neSFQ0qXXxUBenEzmws=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 23.1s
- Final status: failed (capture) in 4.3s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Route stage: route-pending
- Route evidence: 06-26 16:12:38.627 27980 28012 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none

## Transport evidence

- Sender transport mode: `L2CAP`
  - `start()`
  - Startup marker: `06-26 10:12:36.592 26258 26258 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=m7YDySx6jwpD4pNlTIBR9+n0neSFQ0qXXxUBenEzmws= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `L2CAP`
  - `06-26 16:12:28.829 27816 27849 I MeshLinkReferenceAutomation: start() with l2capPsm=146`
  - Startup marker: `06-26 16:12:27.743 27816 27816 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 1.1s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as Mi Note 3
    participant Passive as CPH2359
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (1.1s)
    Passive-->>Matrix: transport start recorded (1.1s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (4.4s)
    Sender->>Passive: send guided payload (4.4s)
    Matrix-->>Matrix: failure summary recorded in report (4.4s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>1.1s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>4.4s]
    D --> E[Send guided payload<br/>4.4s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 10:12:36.592 26258 26258 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=m7YDySx6jwpD4pNlTIBR9+n0neSFQ0qXXxUBenEzmws= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 16:12:27.743 27816 27816 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:12:38.627 27980 28012 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none
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
    "line": "06-26 16:12:27.743 27816 27816 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 1.0,
    "line": "06-26 16:12:28.896 27816 27816 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 2.8,
    "line": "06-26 10:12:36.592 26258 26258 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial targetPeerId=m7YDySx6jwpD4pNlTIBR9+n0neSFQ0qXXxUBenEzmws= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 23.1
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
