# Pair 01 — a065_nam_lx9

## Introduction

Pair 01 (a065_nam_lx9) is a failed initial run over A065 → NAM-LX9. The sender started L2CAP transport, the passive side started GATT transport, and the pair stalled at capture before route establishment.

## Setup

- Sender: A065 (1f1dad34)
- Passive: NAM-LX9 (2ASVB21B09005117)
- Sender API level: 36
- Passive API level: 31
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/01_a065_nam_lx9_report.md`
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/01_a065_nam_lx9_initial`
- Final run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260620T154539/01_a065_nam_lx9_final`
- Target peer id: Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk=

## Result

- Initial status: failed (capture) in 64.5s
- Final status: failed (capture) in 11.9s
- Initial failure reason: Android direct proof stalled at route stage sender=none passive=peer-discovered; senderEvidence=n/a passiveEvidence=06-20 15:45:49.969 12129 12199 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=ac9441
- Final failure reason: Android direct proof stalled at route stage sender=route-unavailable passive=peer-discovered; senderEvidence=06-20 15:46:55.183 21229 21279 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=QDVjk= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk=, topologyVersion=0, routeAvailable=false, attempt=13} passiveEvidence=06-20 15:46:44.845 12401 12433 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=ac9441
- Route stage: route-unavailable
- Route evidence: 06-20 15:46:55.183 21229 21279 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=QDVjk= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk=, topologyVersion=0, routeAvailable=false, attempt=13}

## Transport evidence

- Sender transport mode: `L2CAP`
  - `06-20 15:45:49.595 21089 21131 I MeshLinkReferenceAutomation: start() with l2capPsm=249`
  - Startup marker: `06-20 15:45:49.428 21089 21089 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial`
  - Elapsed: 0.2s
- Passive transport mode: `GATT`
  - `start()`
  - Startup marker: `06-20 15:45:49.097 12129 12129 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial`
  - Elapsed: 0.4s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as A065
    participant Passive as NAM-LX9
    Matrix->>Sender: sender transport start (0.2s)
    Sender-->>Matrix: transport start recorded (0.2s)
    Matrix->>Passive: passive transport start (0.4s)
    Passive-->>Matrix: transport start recorded (0.4s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (12.0s)
    Sender->>Passive: send guided payload (12.0s)
    Matrix-->>Matrix: failure summary recorded in report (12.0s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>0.2s] --> B[Passive transport start<br/>0.4s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>12.0s]
    D --> E[Send guided payload<br/>12.0s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `06-20 15:45:49.428 21089 21089 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial`
- Passive startup marker: `06-20 15:45:49.097 12129 12129 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial`
- Route evidence: 06-20 15:46:55.183 21229 21279 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=QDVjk= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Iz4CzZ99uLSYYXolDgb0WTI+fgHc4ri9Rd9GIAQDVjk=, topologyVersion=0, routeAvailable=false, attempt=13}
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
    "elapsedSeconds": 0.5,
    "line": "06-20 15:45:49.097 12129 12129 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 1.0,
    "line": "06-20 15:45:50.011 12129 12129 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.0,
    "line": "06-20 15:45:49.428 21089 21089 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial",
    "observed": true
  },
  "totalSeconds": 64.5
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
