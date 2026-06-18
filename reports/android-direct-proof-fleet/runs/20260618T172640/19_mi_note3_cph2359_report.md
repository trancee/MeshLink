# Pair 19 — mi_note3_cph2359

## Setup

- Sender: Mi Note 3 (42c2cf)
- Passive: CPH2359 (EQUGS85LJNEIO7Z5)
- Sender API level: 28
- Passive API level: 34
- Transport: GATT
- Fleet inventory: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/fleet.md`
- Pair report path: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/19_mi_note3_cph2359_report.md`
- Peer lookup time: —
- Initial run dir: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/19_mi_note3_cph2359_initial`
- Final run dir: `—`

## Result

- Initial status: failed (capture) in 23.9s
- Final status: skipped (capture) in 23.9s
- Target peer id: not resolved
- Initial HTML report: `summary.html`
- Final HTML report: `summary.html`
- Initial summary JSON: `/home/phil/Projects/MeshLink/reports/android-direct-proof-fleet/runs/20260618T172640/19_mi_note3_cph2359_initial/summary.json`
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
- Fallback reason: android API below 33; using GATT fallback (senderApiLevel=28 passiveApiLevel=34)
- Sender API level 28 is below the floor 33.
- Initial run failure: Android direct proof stalled at route stage sender=none passive=hop-established; senderEvidence=n/a passiveEvidence=06-18 17:39:52.073 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION HOP_SESSION_ESTABLISHED role=PASSIVE peer=55:B2:34:79:76:01
- Final run failure: Android direct proof stalled at route stage sender=none passive=hop-established; senderEvidence=n/a passiveEvidence=06-18 17:39:52.073 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION HOP_SESSION_ESTABLISHED role=PASSIVE peer=55:B2:34:79:76:01

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
    "line": "06-18 17:39:52.019 24003 24003 I MeshLinkProof: MeshLink proof app ready on OPPO CPH2359 (SDK 34) appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "observed": true
  },
  "passiveTransport": {
    "elapsedSeconds": 0.0,
    "line": "06-18 17:39:52.050 24003 24039 I MeshLinkProof: gatt.benchmark.start() -> Started",
    "observed": true
  },
  "sender": {
    "elapsedSeconds": 2.8,
    "line": "06-18 11:39:56.435 24120 24120 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial",
    "observed": true
  },
  "totalSeconds": 23.9
}
```

Initial timings

```json
{
  "androidReadySeconds": 20.0,
  "captureTimeoutSeconds": 30.0,
  "passive": {
    "completionMarker": null,
    "peerDiscoveryMarker": "06-18 17:39:52.051 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 17:39:52.051 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 17:39:52.019 24003 24003 I MeshLinkProof: MeshLink proof app ready on OPPO CPH2359 (SDK 34) appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 17:39:52.052 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "trustConnectionSeconds": 0.001
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 11:39:56.435 24120 24120 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 2.8,
    "transportEvidence": "06-18 11:39:56.643 24120 24120 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 23.9,
  "transportEvidence": "06-18 17:39:52.019 24003 24003 I MeshLinkProof: MeshLink proof app ready on OPPO CPH2359 (SDK 34) appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    "peerDiscoveryMarker": "06-18 17:39:52.051 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "peerDiscoverySeconds": null,
    "receiptSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": "06-18 17:39:52.051 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "startupMarker": null,
    "startupObserved": true,
    "startupWaitSeconds": 0.5,
    "transportEvidence": "06-18 17:39:52.019 24003 24003 I MeshLinkProof: MeshLink proof app ready on OPPO CPH2359 (SDK 34) appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
    "transportMode": "GATT",
    "trustConnectionMarker": "06-18 17:39:52.052 24003 24026 I MeshLinkProof: REFERENCE_AUTOMATION ROUTE_DISCOVERED role=PASSIVE peer=5D:B8:A9:88:FB:B5",
    "trustConnectionSeconds": 0.001
  },
  "sender": {
    "completionMarker": null,
    "peerDiscoveryMarker": null,
    "peerDiscoverySeconds": null,
    "sendCompletionSeconds": null,
    "sendLatencySeconds": null,
    "sendRequestMarker": null,
    "startupMarker": "06-18 11:39:56.435 24120 24120 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 storage=19_mi_note3_cph2359_initial",
    "startupObserved": true,
    "startupWaitSeconds": 2.8,
    "transportEvidence": "06-18 11:39:56.643 24120 24120 I MeshLinkReferenceAutomation: start() with l2capPsm=0",
    "transportMode": "GATT",
    "trustConnectionMarker": null,
    "trustConnectionSeconds": null
  },
  "totalSeconds": 23.9,
  "transportEvidence": "06-18 17:39:52.019 24003 24003 I MeshLinkProof: MeshLink proof app ready on OPPO CPH2359 (SDK 34) appId=demo.meshlink.reference.android-direct.mi_note3_cph2359 powerMode=Automatic primaryTransport=gattPrototype benchmarkTransport=gattPrototype",
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
    participant Passive as CPH2359
    note over Matrix: transport GATT
    note over Matrix: fleet inventory fleet.md
    note over Matrix: pair report 19_mi_note3_cph2359_report.md
    Matrix->>Sender: initial run (23.9s)
    note over Sender: failed (capture)
    alt initial failed
        note over Matrix: fail-fast stop after initial failure
    end
```
