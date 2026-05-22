# Understanding Babel routing in MeshLink

## What problem routing solves

In a mesh network, not every device can reach every other device directly. If A
can reach B and B can reach C, routing lets A send traffic to C through B.

MeshLink adapts ideas from Babel (RFC 8966) for this BLE mesh environment.

## Why Babel fits this problem

Babel is attractive here because it gives MeshLink four useful properties:

1. loop avoidance through the feasibility condition
2. fast convergence after topology changes
3. low control-plane overhead through differential updates
4. a design intended for real wireless links, not only stable wired ones

## How it works in MeshLink

### Neighbor discovery

Nodes exchange Hello messages with immediate neighbors. That is how MeshLink
learns that a direct peer is present and how link quality can start to matter.

### Route advertisement

When a node learns a route, it advertises that route to neighbors with:

- the destination identity hint
- a metric
- a monotonically increasing sequence number

That lets other nodes compare candidate routes and reason about freshness.

### The feasibility condition

The feasibility condition is the loop-prevention rule.

A node accepts a route only when the candidate looks strictly better than the
best metric it has already advertised for that destination. That keeps stale or
loop-forming alternatives from being reintroduced casually.

### Differential updates

MeshLink does not dump the full route table on every change. It sends the route
changes that matter.

That keeps routing traffic smaller and better suited to BLE transport budgets.

### Route retraction

When a route becomes unreachable, MeshLink propagates an explicit withdrawal so
neighbors stop treating it as valid.

## Why sequence numbers matter on reconnect

Reconnect logic is not only about "the peer is back." The routing state also has
to look fresh enough for neighbors to re-accept and re-propagate it.

If reconnect uses stale sequence information, the local node may look healthy
again while neighboring nodes keep older route state and fail to spread the
recovery.

That is why reconnect paths need a fresh seqno progression.

## TTL by priority

Messages do not propagate indefinitely. MeshLink uses these TTL values:

| Priority | TTL |
|---|---|
| `HIGH` | 45 minutes |
| `NORMAL` | 15 minutes |
| `LOW` | 5 minutes |

## Deduplication still matters

Routing alone is not enough. A relay also needs to avoid re-forwarding traffic
it has already seen.

That is why MeshLink keeps a dedup set with TTL- and LRU-style behavior for
already-forwarded messages.

## What host apps should take away

Host apps do not need to implement their own routing policy. The public job is
simpler:

- observe peer and diagnostic events
- send payloads through `MeshLinkApi`
- treat routing behavior as runtime-owned infrastructure

If you want the peer-presence side of this story, read
[The peer lifecycle model](peer-lifecycle.md).
