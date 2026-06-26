# Pair 08 — nam_lx9_mi_note3

## Introduction

Pair 08 (nam_lx9_mi_note3) is a failed initial run over NAM-LX9 → Mi Note 3. The sender started GATT transport, the passive side started GATT transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 77 · final sender ignored 0 · final passive ignored 1

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: NAM-LX9 (2ASVB21B09005117)
- Passive: Mi Note 3 (42c2cf)
- Sender API level: 31
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `GATT`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/08_nam_lx9_mi_note3_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/08_nam_lx9_mi_note3_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/08_nam_lx9_mi_note3_final`
- Target peer id: AHHZv6uRmAVwUMdsyPlK1A7xATDhamZrEfPF+fpX31g=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 20.1s
- Final status: failed (capture) in 4.8s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof sender stalled before peer discovery; classified as a capture stall
- Route stage: route-discovered
- Route evidence: 06-26 16:06:34.012  8318  8362 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.ready role=SENDER peerId=8190b3fd5bdf30c0dff273dd peerSuffix=f273dd trustState=UNKNOWN connectionState=CONNECTED

## Transport evidence

- Sender transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 16:06:29.165  8237  8237 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=AHHZv6uRmAVwUMdsyPlK1A7xATDhamZrEfPF+fpX31g= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-26 10:06:22.352 21115 21115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 1.0s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as NAM-LX9
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

- Sender startup marker: `06-26 16:06:29.165  8237  8237 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=AHHZv6uRmAVwUMdsyPlK1A7xATDhamZrEfPF+fpX31g= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 10:06:22.352 21115 21115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:06:34.012  8318  8362 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.ready role=SENDER peerId=8190b3fd5bdf30c0dff273dd peerSuffix=f273dd trustState=UNKNOWN connectionState=CONNECTED
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
    "line": "06-26 10:06:22.352 21115 21115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 1.3,
    "line": "06-26 10:06:23.740 21115 21115 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.5,
    "line": "06-26 16:06:29.165  8237  8237 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_mi_note3 storage=08_nam_lx9_mi_note3_initial targetPeerId=AHHZv6uRmAVwUMdsyPlK1A7xATDhamZrEfPF+fpX31g= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 20.1
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
