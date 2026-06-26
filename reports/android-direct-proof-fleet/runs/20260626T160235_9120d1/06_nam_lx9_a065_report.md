# Pair 06 — nam_lx9_a065

## Introduction

Pair 06 (nam_lx9_a065) is a failed initial run over NAM-LX9 → A065. The sender started L2CAP transport, the passive side started L2CAP transport, and the pair stalled at capture before route establishment. Foreign scan summary: initial sender ignored 0 · initial passive ignored 69 · final sender ignored 0 · final passive ignored 9

### How to read this report
- The foreign-scan summary in the intro aggregates initial + final runs for this pair.
- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.

## Setup

- Sender: NAM-LX9 (2ASVB21B09005117)
- Passive: A065 (1f1dad34)
- Sender API level: 31
- Passive API level: 36
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/06_nam_lx9_a065_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/06_nam_lx9_a065_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260626T160235_9120d1/06_nam_lx9_a065_final`
- Target peer id: LpiL7e/DHEiCvQNT4NTD1nOKYyYCGn7HiUERcxjGlnA=
- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.
- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.

## Result

- Initial status: failed (capture) in 16.9s
- Final status: failed (capture) in 1.8s
- Initial failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Final failure reason: Android direct proof reached startup but discovery stalled before peer discovery or route readiness; classified as a capture stall
- Route stage: route-pending
- Route evidence: 06-26 16:05:17.397 16687 16708 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none

## Transport evidence

- Sender transport mode: `L2CAP`
  - `start()`
  - Startup marker: `06-26 16:05:15.891  7498  7498 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=LpiL7e/DHEiCvQNT4NTD1nOKYyYCGn7HiUERcxjGlnA= autoStartMesh=true autoSendHello=true`
  - Elapsed: —
- Passive transport mode: `L2CAP`
  - `06-26 16:05:10.289 16593 16617 I MeshLinkReferenceAutomation: start() with l2capPsm=241`
  - Startup marker: `06-26 16:05:10.079 16593 16593 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
  - Elapsed: 0.2s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as NAM-LX9
    participant Passive as A065
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (0.2s)
    Passive-->>Matrix: transport start recorded (0.2s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (1.9s)
    Sender->>Passive: send guided payload (1.9s)
    Matrix-->>Matrix: failure summary recorded in report (1.9s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>0.2s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>1.9s]
    D --> E[Send guided payload<br/>1.9s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-26 16:05:15.891  7498  7498 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=LpiL7e/DHEiCvQNT4NTD1nOKYyYCGn7HiUERcxjGlnA= autoStartMesh=true autoSendHello=true`
- Passive startup marker: `06-26 16:05:10.079 16593 16593 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=none autoStartMesh=true autoSendHello=false`
- Route evidence: 06-26 16:05:17.397 16687 16708 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION route.pending role=PASSIVE count=0 selectedPeerId=none
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
    "elapsedSeconds": 0.5,
    "line": "06-26 16:05:10.079 16593 16593 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=none autoStartMesh=true autoSendHello=false",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.3,
    "line": "06-26 16:05:10.364 16593 16593 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.5,
    "line": "06-26 16:05:15.891  7498  7498 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_a065 storage=06_nam_lx9_a065_initial targetPeerId=LpiL7e/DHEiCvQNT4NTD1nOKYyYCGn7HiUERcxjGlnA= autoStartMesh=true autoSendHello=true",
    "observed": true
  },
  "totalSeconds": 16.9
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
