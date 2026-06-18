# Pair 01 — a065_nam_lx9

## Setup

- Sender: A065 (1f1dad34)
- Passive: NAM-LX9 (2ASVB21B09005117)
- Sender API level: 36
- Passive API level: 31
- Transport: GATT
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/01_a065_nam_lx9_report.md`
- Peer lookup time: 63.8s
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/01_a065_nam_lx9_initial`
- Final run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/01_a065_nam_lx9_final`

## Result

- Initial status: passed (no failure stage) in 27.5s
- Final status: passed (no failure stage) in 18.1s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/01_a065_nam_lx9_initial/summary.json`
- Final summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T133249/01_a065_nam_lx9_final/summary.json`

## Troubleshooting references

| Initial artifact | Path | Captured |
|---|---|---|
| Initial senderLogcat | `sender_logcat.log` | yes |
| Initial passiveLogcat | `passive_logcat.log` | yes |
| Initial senderStart | `sender_start.txt` | yes |
| Initial passiveStart | `passive_start.txt` | yes |
| Initial androidHistory | `android_history.json` | yes |
| Initial androidExport | `android_export.json` | yes |
| Final artifact | Path | Captured |
|---|---|---|
| Final senderLogcat | `sender_logcat.log` | yes |
| Final passiveLogcat | `passive_logcat.log` | yes |
| Final senderStart | `sender_start.txt` | yes |
| Final passiveStart | `passive_start.txt` | yes |
| Final androidHistory | `android_history.json` | yes |
| Final androidExport | `android_export.json` | yes |

## Device quirks and issues

- Transport used for the pair: GATT
- Fallback reason: android API below 33; using GATT fallback (senderApiLevel=36 passiveApiLevel=31)
- Passive API level 31 is below the floor 33.

## Startup timing

Initial startupTiming

```json
{
  "install": {
    "passiveReused": false,
    "passiveSeconds": 7.4,
    "senderReused": false,
    "senderSeconds": 9.0
  },
  "launch": {
    "passiveReused": false,
    "passiveSeconds": null,
    "passiveStartupWaitSeconds": 20.0,
    "passiveTransportWaitSeconds": 20.0,
    "postResultIdleSeconds": 2.0,
    "senderReused": false,
    "senderSeconds": null
  },
  "passive": {
    "elapsedSeconds": 0.5,
    "line": "06-18 13:32:58.667 20014 20014 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.0,
    "line": "06-18 13:32:58.705 20014 20042 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "permissions": {
    "passiveReused": false,
    "passiveSeconds": 0.1,
    "senderReused": false,
    "senderSeconds": 0.1
  },
  "sender": {
    "elapsedSeconds": 0.5,
    "line": "06-18 13:32:59.845 19996 19996 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial",
    "observed": true
  },
  "totalSeconds": 27.5
}
```

Initial timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": "06-18 13:33:01.613 20014 20037 I MeshLinkProof: REFERENCE_AUTOMATION proof.complete role=passive peer=66:92:2E:1C:0C:D6 token=7a8c16ffe10c4a17 bytes=51",
    "peerDiscoveryMarker": "06-18 13:33:00.468 20014 20037 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": 1.145,
    "sendRequestMarker": "06-18 13:33:00.468 20014 20037 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 13:32:58.667 20014 20014 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 13:33:00.469 20014 20037 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "trustConnectionSeconds": 0.001
  },
  "passiveInstallReused": false,
  "sender": {
    "completionMarker": "06-18 13:33:01.947 19996 20035 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=sender",
    "peerDiscoveryMarker": "06-18 13:33:00.228 19996 19996 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=gatt-notify-bridge",
    "peerDiscoverySeconds": 0.383,
    "sendCompletionSeconds": 2.102,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 13:32:59.845 19996 19996 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_initial",
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 13:32:59.855 19996 19996 I MeshLinkReferenceAutomation: gatt.start() -> Started",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 13:33:00.782 19996 20035 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=SENDER peer=gatt-notify-bridge",
    "trustConnectionSeconds": 0.554
  },
  "senderInstallReused": false,
  "totalSeconds": 27.5,
  "transportEvidence": "06-18 13:32:58.667 20014 20014 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
  "transportMode": "GATT"
}
```

Final startupTiming

```json
{
  "install": {
    "passiveReused": true,
    "passiveSeconds": null,
    "senderReused": true,
    "senderSeconds": null
  },
  "launch": {
    "passiveReused": false,
    "passiveSeconds": null,
    "passiveStartupWaitSeconds": 20.0,
    "passiveTransportWaitSeconds": 20.0,
    "postResultIdleSeconds": 2.0,
    "senderReused": false,
    "senderSeconds": null
  },
  "passive": {
    "elapsedSeconds": 0.5,
    "line": "06-18 13:34:20.976 20333 20333 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.0,
    "line": "06-18 13:34:21.013 20333 20366 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "permissions": {
    "passiveReused": false,
    "passiveSeconds": 0.2,
    "senderReused": false,
    "senderSeconds": 0.1
  },
  "sender": {
    "elapsedSeconds": 0.5,
    "line": "06-18 13:34:22.185 20449 20449 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_final",
    "observed": true
  },
  "totalSeconds": 18.1
}
```

Final timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": "06-18 13:34:23.726 20333 20354 I MeshLinkProof: REFERENCE_AUTOMATION proof.complete role=passive peer=66:92:2E:1C:0C:D6 token=f6740680cf304274 bytes=51",
    "peerDiscoveryMarker": "06-18 13:34:22.640 20333 20354 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": 1.086,
    "sendRequestMarker": "06-18 13:34:22.640 20333 20354 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 13:34:20.976 20333 20333 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 13:34:22.641 20333 20354 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=66:92:2E:1C:0C:D6",
    "trustConnectionSeconds": 0.001
  },
  "passiveInstallReused": true,
  "sender": {
    "completionMarker": "06-18 13:34:24.056 20449 20495 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=sender",
    "peerDiscoveryMarker": "06-18 13:34:22.568 20449 20449 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=gatt-notify-bridge",
    "peerDiscoverySeconds": 0.383,
    "sendCompletionSeconds": 1.871,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 13:34:22.185 20449 20449 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.a065_nam_lx9 storage=01_a065_nam_lx9_final",
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 13:34:22.194 20449 20449 I MeshLinkReferenceAutomation: gatt.start() -> Started",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 13:34:22.951 20449 20495 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=SENDER peer=gatt-notify-bridge",
    "trustConnectionSeconds": 0.383
  },
  "senderInstallReused": true,
  "totalSeconds": 18.1,
  "transportEvidence": "06-18 13:34:20.976 20333 20333 I MeshLinkProof: MeshLink proof app ready on HUAWEI NAM-LX9 (SDK 31) appId=demo.meshlink.reference.android-direct.a065_nam_lx9 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
  "transportMode": "GATT"
}
```

Captured evidence map

```json
{
  "final": {
    "androidExport": true,
    "androidHistory": true,
    "passiveLogcat": true,
    "passiveStart": true,
    "senderLogcat": true,
    "senderStart": true
  },
  "initial": {
    "androidExport": true,
    "androidHistory": true,
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
    participant Passive as NAM-LX9
    note over Matrix: transport GATT
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 01_a065_nam_lx9_report.md
    Matrix->>Sender: initial run (27.5s)
    note over Sender: passed (no failure stage)
    alt initial passed
        Matrix->>Passive: read passive peer id (63.8s)
        note over Matrix: target peer not resolved
        Matrix->>Sender: final run (18.1s)
        note over Sender: passed (no failure stage)
        alt final passed
            note over Matrix: pair completed successfully
        else final failed
            note over Matrix: fail-fast stop after final failure
        end
    else initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
