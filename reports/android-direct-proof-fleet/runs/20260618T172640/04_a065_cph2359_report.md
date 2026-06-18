# Pair 04 — a065_cph2359

## Setup

- Sender: A065 (1f1dad34)
- Passive: CPH2359 (EQUGS85LJNEIO7Z5)
- Sender API level: 36
- Passive API level: 34
- Transport: MESHLINK
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/04_a065_cph2359_report.md`
- Peer lookup time: —
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/04_a065_cph2359_initial`
- Final run dir: `—`

## Result

- Initial status: failed (capture) in 63.2s
- Final status: skipped (capture) in 63.2s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/04_a065_cph2359_initial/summary.json`
- Final summary JSON: `—`

## Troubleshooting references

| Initial artifact | Path | Captured |
|---|---|---|
| Initial senderLogcat | `sender_logcat.log` | yes |
| Initial passiveLogcat | `passive_logcat.log` | yes |
| Initial senderStart | `sender_start.txt` | yes |
| Initial passiveStart | `passive_start.txt` | yes |
| Initial androidHistory | `android_history.json` | no |
| Initial androidExport | `android_export.json` | no |
| Final artifact | Path | Captured |
|---|---|---|
| Final senderLogcat | `—` | no |
| Final passiveLogcat | `—` | no |
| Final senderStart | `—` | no |
| Final passiveStart | `—` | no |
| Final androidHistory | `—` | no |
| Final androidExport | `—` | no |

## Device quirks and issues

- Transport used for the pair: MESHLINK
- Initial run failure: Android direct proof stalled at route stage sender=route-unavailable passive=none; senderEvidence=06-18 17:29:49.792  3093  3122 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=92fd1e detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=c8522a3cc82121d84392fd1e, topologyVersion=0, routeAvailable=false, attempt=2} passiveEvidence=n/a
- Final run failure: Android direct proof stalled at route stage sender=route-unavailable passive=none; senderEvidence=06-18 17:29:49.792  3093  3122 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION sender.observed role=sender family=DIAGNOSTIC title=DELIVERY_RETRY_SCHEDULED peer=92fd1e detail=DELIVERY_RETRY_SCHEDULED @ delivery.retryScheduled {peerId=c8522a3cc82121d84392fd1e, topologyVersion=0, routeAvailable=false, attempt=2} passiveEvidence=n/a

## Startup timing

Initial startupTiming

```json
{
  "launch": {
    "passiveStartupWaitSeconds": 20.0,
    "passiveTransportWaitSeconds": 20.0,
    "postResultIdleSeconds": 2.0
  },
  "passive": {
    "elapsedSeconds": 0.8,
    "line": "06-18 17:29:01.859 22682 22682 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.8,
    "line": "06-18 17:29:02.659 22682 22682 I MeshLinkReferenceAutomation: advertising started mode=2 tx=3 connectable=true",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.5,
    "line": "06-18 17:29:39.776  3093  3093 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "observed": true
  },
  "totalSeconds": 63.1
}
```

Initial timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 17:29:01.859 22682 22682 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.8,
    "transportEvidence": "06-18 17:29:01.938 22682 22682 I MeshLinkReferenceAutomation: start() with l2capPsm=160",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": "06-18 17:29:40.180  3093  3119 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=92fd1e",
    "peerDiscoverySeconds": 0.404,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 17:29:40.180  3093  3119 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION send.requested role=sender phase=primary peer=92fd1e priority=NORMAL bytes=23 payload=guided-hello targetIndex=0 requiredPeerCount=1 targetPeerId=auto",
    "startupMarker": "06-18 17:29:39.776  3093  3093 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 17:29:40.176  3093  3093 I MeshLinkReferenceAutomation: scan found 92fd1e mode=L2CAP psm=161 platform=ANDROID addr=62:29:05:0B:CE:5F",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 63.1,
  "transportEvidence": "06-18 17:29:01.938 22682 22682 I MeshLinkReferenceAutomation: start() with l2capPsm=160",
  "transportMode": "L2CAP"
}
```

Final startupTiming

```json
{}
```

Final timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 17:29:01.859 22682 22682 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.8,
    "transportEvidence": "06-18 17:29:01.938 22682 22682 I MeshLinkReferenceAutomation: start() with l2capPsm=160",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": "06-18 17:29:40.180  3093  3119 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=92fd1e",
    "peerDiscoverySeconds": 0.404,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 17:29:40.180  3093  3119 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION send.requested role=sender phase=primary peer=92fd1e priority=NORMAL bytes=23 payload=guided-hello targetIndex=0 requiredPeerCount=1 targetPeerId=auto",
    "startupMarker": "06-18 17:29:39.776  3093  3093 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_cph2359 storage=04_a065_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 17:29:40.176  3093  3093 I MeshLinkReferenceAutomation: scan found 92fd1e mode=L2CAP psm=161 platform=ANDROID addr=62:29:05:0B:CE:5F",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 63.1,
  "transportEvidence": "06-18 17:29:01.938 22682 22682 I MeshLinkReferenceAutomation: start() with l2capPsm=160",
  "transportMode": "L2CAP"
}
```

Captured evidence map

```json
{
  "final": {},
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

## Mermaid sequence diagram

```mermaid
sequenceDiagram
    participant Matrix
    participant Sender as A065
    participant Passive as CPH2359
    note over Matrix: transport MESHLINK
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 04_a065_cph2359_report.md
    Matrix->>Sender: initial run (63.2s)
    note over Sender: failed (capture)
    alt initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
