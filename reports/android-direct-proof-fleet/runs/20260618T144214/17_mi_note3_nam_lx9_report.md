# Pair 17 — mi_note3_nam_lx9

## Setup

- Sender: Mi Note 3 (42c2cf)
- Passive: NAM-LX9 (2ASVB21B09005117)
- Sender API level: 28
- Passive API level: 31
- Transport: GATT
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/17_mi_note3_nam_lx9_report.md`
- Peer lookup time: —
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/17_mi_note3_nam_lx9_initial`
- Final run dir: `—`

## Result

- Initial status: failed (capture) in 44.1s
- Final status: skipped (capture) in 44.1s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T144214/17_mi_note3_nam_lx9_initial/summary.json`
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
- Fallback reason: android API below 33; using GATT fallback (senderApiLevel=28 passiveApiLevel=31)
- Sender API level 28 is below the floor 33.
- Passive API level 31 is below the floor 33.
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
    "elapsedSeconds": 0.5,
    "line": "06-18 14:52:58.231 24073 24073 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.0,
    "line": "06-18 14:52:58.271 24073 24103 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 2.8,
    "line": "06-18 08:53:06.492 16029 16029 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 storage=17_mi_note3_nam_lx9_initial",
    "observed": true
  },
  "totalSeconds": 44.0
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
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 14:52:58.231 24073 24073 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    "startupMarker": "06-18 08:53:06.492 16029 16029 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 storage=17_mi_note3_nam_lx9_initial",
    "startupObserved": true,
    "startupWaitSeconds": 2.8,
    "transportEvidence": "06-18 08:53:06.725 16029 16029 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 44.0,
  "transportEvidence": "06-18 14:52:58.231 24073 24073 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 14:52:58.231 24073 24073 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    "startupMarker": "06-18 08:53:06.492 16029 16029 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 storage=17_mi_note3_nam_lx9_initial",
    "startupObserved": true,
    "startupWaitSeconds": 2.8,
    "transportEvidence": "06-18 08:53:06.725 16029 16029 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 44.0,
  "transportEvidence": "06-18 14:52:58.231 24073 24073 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.mi_note3_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    participant Sender as Mi Note 3
    participant Passive as NAM-LX9
    note over Matrix: transport GATT
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 17_mi_note3_nam_lx9_report.md
    Matrix->>Sender: initial run (44.1s)
    note over Sender: failed (capture)
    alt initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
