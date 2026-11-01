# Which BLE bearer(s) and PHY policy should MeshLink use?

Type: prototype
Status: open
Blocked by: 02, 06, 11

## Question

Device-lab bake-off: GATT (notify/write-without-response) vs L2CAP CoC, 1M vs 2M PHY, MTU/DLE, connection-setup time, throughput, latency, failure rates, across Android↔Android, Android↔iOS, iOS↔iOS and old/new devices. Decide primary bearer, optional upgrades and PHY policy.

## Scope additions (from research, 2026-10-31)

- Measure max concurrent connections per platform and role (iOS is undocumented; Android ~5–6 confirmed in source).
- Test insecure and secure (bonded) CoC across Android↔iOS, both directions.
- Crossed simultaneous connections: define a deterministic keep-one rule (Android 8/9 can't tell the two links apart).
- From the [suite decision](09-security-profile-suites.md): measure first-message latency (classical vs post-quantum, ≈+2.3 KB) and `XXhfs` link-establishment cost (≈2.5 KB) on real devices, plus effective GATT/CoC throughput, to replace the illustrative 10–50 KB/s range.
- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): build the bake-off app on the baseline tuple. Sign iPhones with the shared lab bundle ID `ch.trancee.lab.host` on the free teams, re-signing within 7 days. The simulator never counts as radio evidence.
