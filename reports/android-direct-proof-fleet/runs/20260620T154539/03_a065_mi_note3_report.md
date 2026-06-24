# Pair 03 — a065_mi_note3

## Introduction

Pair 03 (a065_mi_note3) is a failed initial run over A065 → Mi Note 3. The sender started L2CAP transport, the passive side started GATT transport, and the pair stalled at capture before route establishment.

## Setup

- Sender: A065 (1f1dad34)
- Passive: Mi Note 3 (42c2cf)
- Sender API level: 36
- Passive API level: 28
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/03_a065_mi_note3_report.md`
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/03_a065_mi_note3_initial`
- Final run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/03_a065_mi_note3_final`
- Target peer id: 9ZEH7G3SuOr26Pd9tlL+68Nvjxtf8Wwm0piZclTgnhI=

## Result

- Initial status: failed (capture) in 69.0s
- Final status: failed (capture) in 12.2s
- Initial failure reason: Android direct proof stalled at route stage sender=none passive=hop-failed; senderEvidence=n/a passiveEvidence=06-20 09:49:04.173 23929 24122 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION passive.observed role=passive family=DIAGNOSTIC title=HOP_SESSION_FAILED peer=cea4a2 detail=HOP_SESSION_FAILED @ transport.handshake.message1.send {peerId=f556619f934700ab5ecea4a2, topologyVersion=0, routeAvailable=false}
- Final failure reason: Android direct proof stalled at route stage sender=route-unavailable passive=hop-failed; senderEvidence=06-20 15:50:04.457 21970 22004 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=TgnhI= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=9ZEH7G3SuOr26Pd9tlL+68Nvjxtf8Wwm0piZclTgnhI=, topologyVersion=0, routeAvailable=false, attempt=13} passiveEvidence=06-20 09:49:59.437 24400 24425 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION passive.observed role=passive family=DIAGNOSTIC title=HOP_SESSION_FAILED peer=cea4a2 detail=HOP_SESSION_FAILED @ transport.handshake.message1.send {peerId=f556619f934700ab5ecea4a2, topologyVersion=0, routeAvailable=false}
- Route stage: route-unavailable
- Route evidence: 06-20 15:50:04.457 21970 22004 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=TgnhI= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=9ZEH7G3SuOr26Pd9tlL+68Nvjxtf8Wwm0piZclTgnhI=, topologyVersion=0, routeAvailable=false, attempt=13}

## Transport evidence

- Sender transport mode: `L2CAP`
  - `06-20 15:48:55.753 21831 21864 I MeshLinkReferenceAutomation: start() with l2capPsm=253`
  - Startup marker: `06-20 15:48:55.596 21831 21831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial`
  - Elapsed: 0.2s
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-20 09:48:57.624 23929 23929 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial`
  - Elapsed: 0.8s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as A065
    participant Passive as Mi Note 3
    Matrix->>Sender: sender transport start (0.2s)
    Sender-->>Matrix: transport start recorded (0.2s)
    Matrix->>Passive: passive transport start (0.8s)
    Passive-->>Matrix: transport start recorded (0.8s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (12.2s)
    Sender->>Passive: send guided payload (12.2s)
    Matrix-->>Matrix: failure summary recorded in report (12.2s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>0.2s] --> B[Passive transport start<br/>0.8s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>12.2s]
    D --> E[Send guided payload<br/>12.2s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-20 15:48:55.596 21831 21831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial`
- Passive startup marker: `06-20 09:48:57.624 23929 23929 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial`
- Route evidence: 06-20 15:50:04.457 21970 22004 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=TgnhI= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=9ZEH7G3SuOr26Pd9tlL+68Nvjxtf8Wwm0piZclTgnhI=, topologyVersion=0, routeAvailable=false, attempt=13}
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
    "elapsedSeconds": 2.5,
    "line": "06-20 09:48:57.624 23929 23929 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 2.0,
    "line": "06-20 09:48:59.535 23929 23929 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.0,
    "line": "06-20 15:48:55.596 21831 21831 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_mi_note3 storage=03_a065_mi_note3_initial",
    "observed": true
  },
  "totalSeconds": 69.0
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
