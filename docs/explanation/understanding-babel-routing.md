# Understanding Babel Routing in MeshLink

## What problem routing solves

In a mesh network, not every device can see every other device. If A can see B, and B can see C, but A cannot see C — routing makes it possible for A to send a message to C through B.

MeshLink adapts the Babel routing protocol (RFC 8966) for BLE mesh networks.

## Why Babel

Babel is a loop-avoiding distance-vector protocol designed for both wired and wireless networks. Key properties that suit BLE mesh:

1. **Loop-free by construction** — the feasibility condition mathematically prevents routing loops without expensive link-state flooding
2. **Fast convergence** — route changes propagate in O(diameter) time
3. **Low overhead** — differential updates (only send what changed), not full table dumps
4. **Designed for wireless** — handles asymmetric links, packet loss, changing topologies

## How it works in MeshLink

### Neighbor Discovery (Hello messages)

Every node periodically sends Hello messages to immediate neighbors. These establish bidirectional link quality. A peer that stops sending Hellos is detected as disconnected.

### Route Advertisement (Update messages)

When a node learns a route (either directly connected or via another node's Update), it advertises it to its neighbors with:
- **Destination:** key hash of the reachable peer
- **Metric:** cumulative link cost (hop count in MeshLink)
- **Sequence number:** monotonically increasing per-destination, used for freshness

### The Feasibility Condition

This is the core loop-prevention mechanism. A node will only accept a route if its metric is **strictly less than** the feasibility distance (FD) — the best metric this node has ever announced for that destination.

Three-step check:
1. Is the new route's metric < our current FD for this destination?
2. If yes → accept (it's provably loop-free)
3. If no → reject (accepting could create a loop)

The FD is stored separately from route entries and cleared only on explicit retraction — never on expiry. This prevents loops from stale re-advertisements.

### Differential Updates

MeshLink doesn't dump the full routing table periodically. Instead:
- On topology change: send only the affected routes
- Route digest (FNV-1a hash) lets peers detect drift without full exchange
- Full dump only when a new neighbor appears or digest mismatch detected

### Route Retraction

When a route becomes unreachable:
1. Node sends Update with metric = infinity (retraction)
2. Feasibility distance for that destination is cleared
3. Neighbors propagate the retraction

## The seqNo Problem (and fix)

### The bug (M002–M003)

When a peer disconnects and reconnects, `RouteCoordinator.onPeerConnected()` originally installed the route with `seqNo = 0u`. But the differential routing engine skips re-propagation when seqNo hasn't changed — so neighbors never learned the route was back.

### The fix (M005)

Per-destination seqNo counter starting at 1u, incremented on each reconnect. The routing engine sees a fresh seqNo and propagates the re-installation to all neighbors.

## TTL per priority

Messages have a time-to-live that limits how far they propagate:

| Priority | TTL |
|----------|-----|
| HIGH | 45 minutes |
| NORMAL | 15 minutes |
| LOW | 5 minutes |

## DedupSet

Every node maintains a dedup set (TTL + LRU) to prevent re-relaying messages it has already forwarded. Keys are `List<Byte>` (because `ByteArray` cannot be a HashMap key due to identity-based hashCode).

## PresenceTracker

Tracks peer lifecycle through three states:

```
Connected → Disconnected → Gone
    ↑                         │
    └─────── (reconnect) ─────┘
```

- **Connected:** Active BLE link, Hello exchanges flowing
- **Disconnected:** Link lost, 60-second grace period (peer might return)
- **Gone:** Evicted after two MeshStateManager sweeps (30s each). All routing/trust/transfer state for this peer is cleaned up.
