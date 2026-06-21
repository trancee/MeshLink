# Pair 04 — a065_cph2359

## Introduction

Pair 04 (a065_cph2359) is a failed initial run over A065 → CPH2359. The sender started L2CAP transport, the passive side started L2CAP transport, and the pair stalled at capture before route establishment.

## Setup

- Sender: A065 (1f1dad34)
- Passive: CPH2359 (EQUGS85LJNEIO7Z5)
- Sender API level: 36
- Passive API level: 34
- Sender connection: 🔌 USB
- Passive connection: 🔌 USB
- Matrix transport summary: `L2CAP`
- Pair report path: `reports/android-direct-proof-fleet/runs/20260620T221630/04_a065_cph2359_report.md`
- Fleet inventory: `reports/android-direct-proof-fleet/runs/20260620T221630/fleet.md`
- Peer lookup time: 0.0s
- Initial run dir: `reports/android-direct-proof-fleet/runs/20260620T221630/04_a065_cph2359_initial`
- Final run dir: `reports/android-direct-proof-fleet/runs/20260620T221630/04_a065_cph2359_final`
- Target peer id: Z4x5NDdUsOE0ScjLYc4vn2XtUn+qi54vb8S48d3DHl4=

## Result

- Initial status: failed (launch) in 93.6s
- Final status: failed (capture) in 12.2s
- Initial failure reason: Android peer-resolution gate failed: passive peer id was not resolved before the route phase
- Final failure reason: Android direct proof stalled at route stage sender=route-unavailable passive=none; senderEvidence=06-20 22:23:58.449 12410 12437 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=3DHl4= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Z4x5NDdUsOE0ScjLYc4vn2XtUn+qi54vb8S48d3DHl4=, topologyVersion=0, routeAvailable=false, attempt=13} passiveEvidence=n/a
- Route stage: route-unavailable
- Route evidence: 06-20 22:23:58.449 12410 12437 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=3DHl4= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Z4x5NDdUsOE0ScjLYc4vn2XtUn+qi54vb8S48d3DHl4=, topologyVersion=0, routeAvailable=false, attempt=13}

## Transport evidence

- Sender transport mode: `L2CAP`
  - `06-20 22:22:24.139 12150 12187 I MeshLinkReferenceAutomation: start() with l2capPsm=160`
  - Startup marker: `—`
  - Elapsed: —
- Passive transport mode: `L2CAP`
  - `06-20 22:22:28.183 22398 22438 I MeshLinkReferenceAutomation: start() with l2capPsm=129`
  - Startup marker: `06-20 22:22:27.852 22398 22398 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial`
  - Elapsed: 0.3s
- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Matrix
    participant Sender as A065
    participant Passive as CPH2359
    Matrix->>Sender: sender transport start (—)
    Sender-->>Matrix: transport start recorded (—)
    Matrix->>Passive: passive transport start (0.3s)
    Passive-->>Matrix: transport start recorded (0.3s)
    Matrix->>Matrix: wait for passive peer id (0.0s)
    Sender->>Passive: discovery and route establishment (12.2s)
    Sender->>Passive: send guided payload (12.2s)
    Matrix-->>Matrix: failure summary recorded in report (12.2s)
```

## Mermaid timeline

```mermaid
flowchart LR
    A[Sender transport start<br/>—] --> B[Passive transport start<br/>0.3s]
    B --> C[Wait for passive peer id<br/>0.0s]
    C --> D[Discovery and route establishment<br/>12.2s]
    D --> E[Send guided payload<br/>12.2s]
    E --> F[Failure explanation<br/>see Result section]
```

## Connections

- Sender: 🔌 USB
- Passive: 🔌 USB

## Evidence summary

- Sender startup marker: `—`
- Passive startup marker: `06-20 22:22:27.852 22398 22398 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial`
- Route evidence: 06-20 22:23:58.449 12410 12437 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=3DHl4= detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=Z4x5NDdUsOE0ScjLYc4vn2XtUn+qi54vb8S48d3DHl4=, topologyVersion=0, routeAvailable=false, attempt=13}
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
    "elapsedSeconds": 0.8,
    "line": "06-20 22:22:27.852 22398 22398 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.8,
    "line": "06-20 22:22:28.586 22398 22398 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": null,
    "line": null,
    "observed": false
  },
  "totalSeconds": 93.6
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
