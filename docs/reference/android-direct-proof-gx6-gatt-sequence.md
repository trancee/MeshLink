# Android direct-proof GX6 GATT pair sequence

Last verified: 2026-06-16

This note summarizes the latest attached-device pair tested in the Android direct-proof flow.

## Pair under test

- **First device (sender / reference app):** `A065` (`1f1dad34`)
- **Second device (passive / proof app):** `GX6CTR500184`
- **Observed transport mode:** `GATT`
- **Result:** route progression improved, but retained completion still did not arrive on this run

## Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant A as A065 (1f1dad34, sender/reference)
    participant B as GX6CTR500184 (passive/proof)

    Note over A: startup observed +0.5s<br/>22:42:49.352
    Note over B: startup observed +0.3s<br/>22:42:49.647

    B->>B: +0.031s GATT benchmark server opening<br/>22:42:49.678
    B->>A: +0.033s peer.discovered role=PASSIVE<br/>22:42:49.681
    B->>A: +0.035s ROUTE_DISCOVERED role=PASSIVE<br/>22:42:49.683
    B->>A: +0.036s HOP_SESSION_ESTABLISHED role=PASSIVE<br/>22:42:49.684

    A->>A: +0.381s peer.discovered role=SENDER<br/>22:42:49.733
    A->>B: +0.750s ROUTE_DISCOVERED role=SENDER<br/>22:42:50.102
    B->>B: +0.031s GATT notify start -> Started<br/>22:42:49.678
    B->>B: +0.038s HOP_SESSION_ESTABLISHED role=PASSIVE<br/>22:42:49.684

    Note over A: sender service discovery retry #2<br/>22:42:54.384
    Note over A: sender failed with SERVICE_DISCOVERY_TIMEOUT<br/>22:42:55.890

    Note over A,B: total run 24.2s<br/>capture timeout 45.0s<br/>transportMode=GATT
```

## Timing summary

### Sender side (`A065`)

- Startup wait: **0.5s**
- Peer discovery: **0.381s** after startup
- Route discovery: **0.369s** after peer discovery
- Service discovery retry: **+1.506s** after route discovery
- Failure: **SERVICE_DISCOVERY_TIMEOUT**

### Passive side (`GX6CTR500184`)

- Startup wait: **0.3s**
- Passive peer discovery: **0.031s** after startup
- Passive route discovery: **0.002s** after peer discovery
- Passive hop established: **0.001s** after route discovery

## Outcome

The passive device reached the route stage quickly, but the sender-side GATT client never completed service discovery on this run. That kept the pair from reaching retained completion.
