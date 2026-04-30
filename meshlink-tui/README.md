# MeshLink TUI

Interactive terminal UI for testing MeshLink with a virtual mesh transport. No BLE hardware required — all nodes run in-process with simulated L2CAP channels.

## Running

```bash
./meshlink-tui/run.sh
# or manually:
./gradlew :meshlink-tui:run --console=plain
```

## Features

### Live Mesh Visualization
- **Log tab** — Real-time event stream (peer discovery, handshakes, messages, errors)
- **Peers tab** — Connected peers for the selected node with state and trust mode
- **Routing tab** — Babel routing table (destination, next hop, cost, seqno, age)
- **Health tab** — Per-node health metrics (connected peers, buffer usage, power mode)
- **Network tab** — Topology management with visual node list and link map
- **Send tab** — Unicast and broadcast message sending

### Dynamic Topology Management
Add and remove nodes at runtime, link/unlink them, and observe how the mesh adapts:
- Add nodes on the fly (auto-named or custom-named)
- Remove nodes and watch routes reconverge
- Link/unlink pairs to reshape topology
- Pause/resume individual nodes
- Simulate disconnects and reconnects

### Scenario Presets
Switch between topologies instantly via command mode (`:` key):
| Command | Topology |
|---------|----------|
| `:star` | Hub-and-spoke (node 0 is hub) |
| `:ring` | Ring (each node connects to next, last to first) |
| `:line` | Line (A↔B↔C↔D, no shortcuts) |
| `:mesh` | Full mesh (every node linked to every other) |
| `:partition` | Network split (removes cross-half links) |
| `:heal` | Reconnects the partition boundary |

### Stress Testing
- `:flood <from> <to> <count>` — burst N messages between two nodes
- Observe delivery under different topologies and failure conditions

## Controls

### Global
| Key | Action |
|-----|--------|
| `1`–`6` | Switch tabs |
| `←` / `→` | Switch active node |
| `↑` / `↓` | Scroll log / move cursor |
| `:` | Enter command mode |
| `q` | Quit |

### Network Tab
| Key | Action |
|-----|--------|
| `a` | Add new node (auto-starts) |
| `d` | Remove cursor-selected node |
| `p` | Pause/resume cursor-selected node |
| `l` | Link cursor node ↔ next node |
| `u` | Unlink cursor node ↔ next node |
| `x` | Simulate disconnect (peer-lost to all neighbours) |
| `r` | Reconnect (re-trigger discovery with neighbours) |

### Send Tab
| Key | Action |
|-----|--------|
| `i` | Enter input mode |
| `Enter` | Send message |
| `Esc` | Cancel input |
| `Tab` | Switch target node |
| `b` | Broadcast "Hello mesh!" |

### Command Mode
Type `:` to enter, then a command, then `Enter`. Press `Esc` to cancel.

```
:star                  Star topology
:ring                  Ring topology
:line                  Line topology
:mesh                  Full mesh
:partition             Split network
:heal                  Reconnect split
:add <name>            Add named node
:flood <from> <to> <n> Burst messages
:link <a> <b>          Link by index
:unlink <a> <b>        Unlink by index
:help                  List commands
```

## Example Workflows

### Test route convergence after partition
1. Start with 4+ nodes in line topology: `:line`
2. Partition: `:partition` — observe routes drop
3. Heal: `:heal` — watch routes reconverge via Babel

### Test relay through intermediary
1. Default 3-node line: Alice↔Bob↔Charlie
2. Go to Send tab (`6`), select Alice (`←`), target Charlie (`Tab`)
3. Type a message — it routes through Bob

### Stress test under failure
1. `:mesh` for full connectivity
2. `:flood 0 2 100` to burst 100 messages
3. While flooding, press `5` (Network tab), select a node, press `x` to disconnect
4. Watch delivery failures and retransmission in the Log tab
