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
