# Pair 02 — a065_xcover

## Introduction

Pair 02 (a065_xcover) is a failed initial run over A065 → SM-G390F. The sender started L2CAP transport, the passive side started GATT transport, and the pair stalled at capture before route establishment.

## Setup

- Sender: A065 (1f1dad34)
- Passive: SM-G390F (42004386e43c8589)
- Sender API level: 36
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260620T172500-t02/02_a065_xcover_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260620T172500-t02/fleet.md`
- Peer lookup time: 0.1s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260620T172500-t02/02_a065_xcover_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260620T172500-t02/02_a065_xcover_final`
- Target peer id: OmZOC/HnmEPPE0MA5SCAGJWGpwUB8KHWUCXrg52qtUA=

## Result

- Initial status: failed (capture) in 84.4s
- Final status: failed (capture) in 26.5s
- Initial failure reason: Android direct proof stalled at route stage sender=route-unavailable passive=hop-failed; senderEvidence=06-20 19:32:05.434  1115  1253 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.wait.retry role=sender reason=route-unavailable attempt=10 delayMs=5000 passiveEvidence=06-20 19:31:39.820 25831 26060 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION passive.observed role=passive family=DIAGNOSTIC title=HOP_SESSION_FAILED peer=e02d9e detail=HOP_SESSION_FAILED @ transport.handshake.message1.send {peerId=587e40caeaeaa7d878e02d9e, topologyVersion=0, routeAvailable=false}
- Final failure reason: Android direct proof stalled at route stage sender=route-unavailable passive=hop-failed; senderEvidence=06-20 19:32:56.015  1449  1484 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.wait.retry role=sender reason=route-unavailable attempt=6 delayMs=5000 passiveEvidence=06-20 19:32:49.800 26319 26341 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION passive.observed role=passive family=DIAGNOSTIC title=HOP_SESSION_FAILED peer=e02d9e detail=HOP_SESSION_FAILED @ transport.handshake.message1.send {peerId=587e40caeaeaa7d878e02d9e, topologyVersion=0, routeAvailable=false}
- Route stage: route-unavailable
- Route evidence: 06-20 19:32:56.015  1449  1484 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.wait.retry role=sender reason=route-unavailable attempt=6 delayMs=5000

## Transport evidence

- Sender transport mode: `L2CAP`
  - `06-20 19:31:20.316  1115  1248 I MeshLinkReferenceAutomation: start() with l2capPsm=147`
  - Startup marker: `06-20 19:31:20.154  1115  1115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial`
  - Elapsed: 0.2s
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-20 19:31:29.406 25831 25831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial`
  - Elapsed: 2.4s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as A065
    participant Passive as SM-G390F
    Matrix->>Sender: sender transport start (0.2s)
    Sender-->>Matrix: transport start recorded (0.2s)
    Matrix->>Passive: passive transport start (2.4s)
    Passive-->>Matrix: transport start recorded (2.4s)
    Matrix->>Matrix: wait for passive peer id (0.1s)
    Sender->>Passive: discovery and route establishment (26.5s)
    Sender->>Passive: send guided payload (26.5s)
    Matrix-->>Matrix: failure summary recorded in report (26.5s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>0.2s] --> B[Passive transport start<br/>2.4s]
    B --> C[Wait for passive peer id<br/>0.1s]
    C --> D[Discovery and route establishment<br/>26.5s]
    D --> E[Send guided payload<br/>26.5s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-20 19:31:20.154  1115  1115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial`
- Passive startup marker: `06-20 19:31:29.406 25831 25831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial`
- Route evidence: 06-20 19:32:56.015  1449  1484 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.wait.retry role=sender reason=route-unavailable attempt=6 delayMs=5000
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
    "elapsedSeconds": 9.5,
    "line": "06-20 19:31:29.406 25831 25831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 6.0,
    "line": "06-20 19:31:35.431 25831 25831 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.0,
    "line": "06-20 19:31:20.154  1115  1115 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_xcover storage=02_a065_xcover_initial",
    "observed": true
  },
  "totalSeconds": 84.4
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
