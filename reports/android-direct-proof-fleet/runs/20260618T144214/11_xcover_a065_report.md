# Pair 11 — xcover_a065

## Setup

- Sender: SM-G390F (42004386e43c8589)
- Passive: A065 (1f1dad34)
- Sender API level: 28
- Passive API level: 36
- Transport: GATT
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/11_xcover_a065_report.md`
- Peer lookup time: —
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/11_xcover_a065_initial`
- Final run dir: `—`

## Result

- Initial status: failed (capture) in 33.3s
- Final status: skipped (capture) in 33.3s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/11_xcover_a065_initial/summary.json`
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

- Transport used for the pair: GATT
- Fallback reason: android API below 33; using GATT fallback (senderApiLevel=28 passiveApiLevel=36)
- Sender API level 28 is below the floor 33.
- Initial run failure: Android direct proof stalled at route stage sender=none passive=hop-established; senderEvidence=n/a passiveEvidence=06-18 14:49:11.928 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION HOP_SESSION_ESTABLISHED role=PASSIVE peer=64:64:04:67:E3:20
- Final run failure: Android direct proof stalled at route stage sender=none passive=hop-established; senderEvidence=n/a passiveEvidence=06-18 14:49:11.928 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION HOP_SESSION_ESTABLISHED role=PASSIVE peer=64:64:04:67:E3:20

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
    "elapsedSeconds": 0.3,
    "line": "06-18 14:49:11.914 24865 24865 I MeshLinkProof: MeshLink proof app ready on Nothing A065 (SDK 36) appId=demo.meshlink.reference.android-direct.xcover_a065 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.0,
    "line": "06-18 14:49:11.927 24865 24903 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 9.3,
    "line": "06-18 14:49:20.683 25059 25059 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_a065 storage=11_xcover_a065_initial",
    "observed": true
  },
  "totalSeconds": 33.2
}
```

Initial timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": null,
    "peerDiscoveryMarker": "06-18 14:49:11.926 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 14:49:11.926 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.3,
    "transportEvidence": "06-18 14:49:11.914 24865 24865 I MeshLinkProof: MeshLink proof app ready on Nothing A065 (SDK 36) appId=demo.meshlink.reference.android-direct.xcover_a065 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 14:49:11.927 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "trustConnectionSeconds": 0.001
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 14:49:20.683 25059 25059 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_a065 storage=11_xcover_a065_initial",
    "startupObserved": true,
    "startupWaitSeconds": 9.3,
    "transportEvidence": "06-18 14:49:21.297 25059 25059 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 33.2,
  "transportEvidence": "06-18 14:49:11.914 24865 24865 I MeshLinkProof: MeshLink proof app ready on Nothing A065 (SDK 36) appId=demo.meshlink.reference.android-direct.xcover_a065 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
  "transportMode": "GATT"
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
    "peerDiscoveryMarker": "06-18 14:49:11.926 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 14:49:11.926 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.3,
    "transportEvidence": "06-18 14:49:11.914 24865 24865 I MeshLinkProof: MeshLink proof app ready on Nothing A065 (SDK 36) appId=demo.meshlink.reference.android-direct.xcover_a065 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 14:49:11.927 24865 24880 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=62:A7:4A:C4:F9:02",
    "trustConnectionSeconds": 0.001
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 14:49:20.683 25059 25059 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.xcover_a065 storage=11_xcover_a065_initial",
    "startupObserved": true,
    "startupWaitSeconds": 9.3,
    "transportEvidence": "06-18 14:49:21.297 25059 25059 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 33.2,
  "transportEvidence": "06-18 14:49:11.914 24865 24865 I MeshLinkProof: MeshLink proof app ready on Nothing A065 (SDK 36) appId=demo.meshlink.reference.android-direct.xcover_a065 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
  "transportMode": "GATT"
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
    participant Sender as SM-G390F
    participant Passive as A065
    note over Matrix: transport GATT
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 11_xcover_a065_report.md
    Matrix->>Sender: initial run (33.3s)
    note over Sender: failed (capture)
    alt initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
