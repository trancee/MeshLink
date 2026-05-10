/speckit.specify
MeshLink is a **library-first SDK** for encrypted, serverless, offline-capable
messaging over **Bluetooth Low Energy (BLE) mesh networks** — no internet, no
servers, no user accounts.

**Core capabilities:**
- Multi-hop mesh routing (Babel-based, proactive route propagation)
- Two-layer encryption — Noise XX hop-by-hop + Noise K end-to-end
- Large message transfer with chunking, SACK, and flow control
- Power-aware operation (auto-adjusts radio behavior by battery state)
- Cross-platform — Android, iOS (shared code via KMP)

/speckit.plan Use KMP for implementation with no external dependencies
