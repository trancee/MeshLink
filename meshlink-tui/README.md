# MeshLink TUI

Interactive terminal user interface for testing MeshLink locally using virtual transport — no BLE hardware required.

## What it does

Spins up a **3-node virtual mesh** (Alice ↔ Bob ↔ Charlie) in a line topology:
- Alice and Charlie can only reach each other through Bob (relay)
- All nodes run full MeshLink engines with Noise XX handshakes, Babel routing, and store-and-forward

## Running

**Option 1: Fat JAR (recommended)**  — Direct terminal access, no Gradle I/O wrapping:

```bash
./gradlew :meshlink-tui:fatJar
java -jar meshlink-tui/build/libs/meshlink-tui-all.jar
```

**Option 2: Convenience script** — Builds and runs in one step:

```bash
./meshlink-tui/run.sh
```

**Option 3: Gradle task** — Works but may have terminal issues:

```bash
./gradlew :meshlink-tui:jvmRun --console=plain
```

> **Note:** Gradle wraps stdin/stdout which can interfere with JLine's raw mode.
> If the TUI appears blank, use the fat JAR directly instead.

## Controls

| Key | Action |
|-----|--------|
| `1`–`5` | Switch tabs: Log, Peers, Routing, Health, Send |
| `←` / `→` | Switch active node (shown in top-right) |
| `↑` / `↓` | Scroll event log |
| `i` | Enter input mode (Send tab) |
| `Enter` | Send message (in input mode) |
| `Esc` | Cancel input |
| `Tab` | Switch send target (Send tab) |
| `b` | Broadcast message (Send tab) |
| `q` | Quit |

## Tabs

- **Log** — Real-time event log: discoveries, messages, state changes
- **Peers** — Peer table for the selected node (ID, state, trust mode)
- **Routing** — Babel routing table snapshot (destination, next-hop, cost, seqNo)
- **Health** — Mesh health metrics for all nodes (connections, buffer, power mode)
- **Send** — Interactive message sending between nodes

## Architecture

```
meshlink-tui/src/jvmMain/kotlin/ch/trancee/meshlink/tui/
├── core/           TUI infrastructure (Buffer, Style, Layout, Terminal backend)
├── mesh/           VirtualMeshNetwork (3-node topology with event logging)
├── widgets/        Reusable TUI widgets (Block, renderLines, statusBar)
├── MeshLinkTui.kt  Main application (render loop, tab views, input handling)
└── Main.kt         Entry point
```

Uses `stty` for raw terminal mode and ANSI escape sequences. Implements a Ratatui-style immediate-mode rendering architecture with double-buffered diff-flush for efficient terminal updates.

## Dependencies

- `:meshlink` — The MeshLink library (includes VirtualMeshTransport in `testing` package)
- `kotlinx-coroutines-core` — Async event loop

No external terminal library needed — raw mode via `stty`, input from `/dev/tty`.
