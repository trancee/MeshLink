# Pair 23 — cph2359_xcover

## Introduction

Pair 23 (cph2359_xcover) is a failed initial run over CPH2359 → SM-G390F. The sender started L2CAP transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 62 · final sender ignored 0 · final passive ignored 22

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: CPH2359 (EQUGS85LJNEIO7Z5)
- Passive: SM-G390F (42004386e43c8589)
- Sender API level: 34
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/23_cph2359_xcover_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.1s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/23_cph2359_xcover_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/23_cph2359_xcover_final`
- Target peer id: oTQG++QKmI+iMVxCG55vh+zsZz42NG+nRVuwitJ4404=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 32.2s
- Final status: failed (capture) in 13.5s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof stalled at route stage sender=sender-discovery-stalled passive=route-discovered; senderEvidence=06-26 16:14:30.409 29268 29297 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.discovery.stalled role=SENDER count=0 selectedPeerId=none elapsedSeconds=3.0 passiveEvidence=06-26 16:14:28.494 14486 14519 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.ready role=PASSIVE peerId=522542eb6bbb616b4e013e0a peerSuffix=013e0a trustState=UNKNOWN connectionState=CONNECTED
- Route stage: sender-discovery-stalled
- Route evidence: 06-26 16:14:30.409 29268 29297 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.discovery.stalled role=SENDER count=0 selectedPeerId=none elapsedSeconds=3.0

## Transport evidence

- Sender transport mode: `L2CAP`
  - `start()`
  - Startup marker: `06-26 16:14:17.002 29171 29171 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=oTQG++QKmI+iMVxCG55vh+zsZz42NG+nRVuwitJ4404= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:14:05.326 14339 14339 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 2.9s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as CPH2359
    participant Passive as SM-G390F
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (2.9s)
    Passive-->>Matrix: transport start recorded (2.9s)
    Matrix->>Matrix: wait for passive peer id (0.1s)
    Sender->>Passive: discovery and route establishment (13.6s)
    Sender->>Passive: send guided payload (13.6s)
    Matrix-->>Matrix: failure summary recorded in report (13.6s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>2.9s]
    B --> C[Wait for passive peer id<br/>0.1s]
    C --> D[Discovery and route establishment<br/>13.6s]
    D --> E[Send guided payload<br/>13.6s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 16:14:17.002 29171 29171 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=oTQG++QKmI+iMVxCG55vh+zsZz42NG+nRVuwitJ4404= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 16:14:05.326 14339 14339 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:14:30.409 29268 29297 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.discovery.stalled role=SENDER count=0 selectedPeerId=none elapsedSeconds=3.0
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
    "elapsedSeconds": 8.8,
    "line": "06-26 16:14:05.326 14339 14339 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 4.0,
    "line": "06-26 16:14:09.345 14339 14339 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.8,
    "line": "06-26 16:14:17.002 29171 29171 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.cph2359_xcover storage=23_cph2359_xcover_initial targetPeerId=oTQG++QKmI+iMVxCG55vh+zsZz42NG+nRVuwitJ4404= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 32.2
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
