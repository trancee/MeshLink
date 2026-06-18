# Pair 07 — nam_lx9_xcover

## Setup

- Sender: NAM-LX9 (2ASVB21B09005117)
- Passive: SM-G390F (42004386e43c8589)
- Sender API level: 31
- Passive API level: 28
- Transport: GATT
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/07_nam_lx9_xcover_report.md`
- Peer lookup time: —
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/07_nam_lx9_xcover_initial`
- Final run dir: `—`

## Result

- Initial status: failed (capture) in 44.8s
- Final status: skipped (capture) in 44.8s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/07_nam_lx9_xcover_initial/summary.json`
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
- Fallback reason: android API below 33; using GATT fallback (senderApiLevel=31 passiveApiLevel=28)
- Sender API level 31 is below the floor 33.
- Passive API level 28 is below the floor 33.
- Initial run failure: Timed out waiting for proof.complete on Android roles: passive, sender
- Final run failure: Timed out waiting for proof.complete on Android roles: passive, sender

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
    "elapsedSeconds": 1.3,
    "line": "06-18 17:31:46.162 31818 31818 I MeshLinkProof: MeshLink proof app ready on samsung SM-G390F (SDK 28) appId=demo.meshlink.reference.android-direct.nam_lx9_xcover powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.3,
    "line": "06-18 17:31:46.293 31818 31872 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 0.8,
    "line": "06-18 17:31:47.768 29691 29691 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_xcover storage=07_nam_lx9_xcover_initial",
    "observed": true
  },
  "totalSeconds": 44.7
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
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 1.3,
    "transportEvidence": "06-18 17:31:46.162 31818 31818 I MeshLinkProof: MeshLink proof app ready on samsung SM-G390F (SDK 28) appId=demo.meshlink.reference.android-direct.nam_lx9_xcover powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 17:31:47.768 29691 29691 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_xcover storage=07_nam_lx9_xcover_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.8,
    "transportEvidence": "06-18 17:31:47.880 29691 29691 I MeshLinkReferenceAutomation: start() with l2capPsm=179",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 44.7,
  "transportEvidence": "06-18 17:31:46.162 31818 31818 I MeshLinkProof: MeshLink proof app ready on samsung SM-G390F (SDK 28) appId=demo.meshlink.reference.android-direct.nam_lx9_xcover powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 1.3,
    "transportEvidence": "06-18 17:31:46.162 31818 31818 I MeshLinkProof: MeshLink proof app ready on samsung SM-G390F (SDK 28) appId=demo.meshlink.reference.android-direct.nam_lx9_xcover powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 17:31:47.768 29691 29691 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.nam_lx9_xcover storage=07_nam_lx9_xcover_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.8,
    "transportEvidence": "06-18 17:31:47.880 29691 29691 I MeshLinkReferenceAutomation: start() with l2capPsm=179",
    "transportMode": "L2CAP",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 44.7,
  "transportEvidence": "06-18 17:31:46.162 31818 31818 I MeshLinkProof: MeshLink proof app ready on samsung SM-G390F (SDK 28) appId=demo.meshlink.reference.android-direct.nam_lx9_xcover powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    participant Sender as NAM-LX9
    participant Passive as SM-G390F
    note over Matrix: transport GATT
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 07_nam_lx9_xcover_report.md
    Matrix->>Sender: initial run (44.8s)
    note over Sender: failed (capture)
    alt initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
