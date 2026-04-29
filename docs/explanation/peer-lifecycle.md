# The Peer Lifecycle Model

## The problem

In a BLE mesh, peers appear and disappear constantly. A phone moves out of range, the OS suspends BLE, a connection drops due to interference. The mesh must handle this gracefully.

The naive approach: when a BLE connection drops, immediately forget the peer. This causes:
- Routes flapping on momentary interference
- Transfer sessions being abandoned unnecessarily
- "Ghost" discoveries when a peer goes and returns within seconds

## The three-state model

```
         ┌─── BLE connected ───┐
         │                      │
         ▼                      │
    CONNECTED ──── link lost ────► DISCONNECTED ──── 2 sweeps (60s) ────► GONE
         ▲                                │
         │                                │
         └──── BLE reconnects ────────────┘
```

### Connected

- Active BLE link
- Hello messages exchanged (Babel neighbor discovery)
- Transfers in progress
- Route entries marked as live

### Disconnected

- BLE link lost
- **Grace period active** — the peer might return
- Routes marked as degraded (higher metric) but not retracted
- Transfers paused (not abandoned — can resume)
- Peer still appears in `allPeerDetails()` with `state = DISCONNECTED`

### Gone

- Peer has been disconnected through two MeshStateManager sweeps (60 seconds total)
- All state is cleaned up:
  - Routes retracted
  - Trust entries preserved (TOFU key stays pinned)
  - Transfer sessions abandoned
  - Presence removed
- `PeerEvent.Lost` emitted to the consuming app

## Why two sweeps?

A single-sweep eviction would fire at exactly 30 seconds. But:

1. A sweep might run 1ms after disconnect — effectively 0-second grace
2. Or it might run 29.9s after — almost full grace

Two sweeps guarantee **at least 30 seconds and at most 60 seconds** of grace, regardless of when the disconnect happened relative to the sweep timer.

The `sweepCount >= 2` condition means a peer must survive two full sweep cycles before eviction.

## MeshStateManager

The orchestrator that drives the lifecycle:

- Runs every 30 seconds
- Checks all peers in `DISCONNECTED` state
- Increments `sweepCount` for each
- Evicts peers where `sweepCount >= 2`
- Coordinates cleanup across all subsystems (routing, transfer, presence)

## Why "Gone" is not a public state

The public API exposes only:

```kotlin
enum class PeerState { CONNECTED, DISCONNECTED }
```

There is no `GONE` value. When a peer transitions to Gone:
- It's evicted from all state
- `PeerEvent.Lost` fires
- It disappears from `allPeerDetails()`

Exposing `GONE` as a public state would create confusion: "I have a peer in GONE state — can I send to it? Can I query it?" The answer is always no. Lost means gone.

## The seqNo interaction

When a peer reconnects (Disconnected → Connected), the route must be re-installed with an incremented seqNo. Without this (the original M002 bug), differential routing sees `seqNo == previousSeqNo` and skips re-propagation to neighbors.

The fix: `RouteCoordinator` maintains a per-destination seqNo counter starting at 1, incremented on each `onPeerConnected()`. This ensures the routing engine always propagates the re-installed route.

## Impact on the consuming app

From the app's perspective:

```kotlin
meshLink.peerEvents.collect { events ->
    for (event in events) {
        when (event) {
            is PeerEvent.Found -> addPeerToUI(event.peerId)
            is PeerEvent.StateChanged -> updatePeerState(event.peerId, event.state)
            is PeerEvent.Lost -> removePeerFromUI(event.peerId)
        }
    }
}
```

The app never needs to manage timeouts or grace periods — MeshLink handles the lifecycle internally and only surfaces clean Found/StateChanged/Lost transitions.
