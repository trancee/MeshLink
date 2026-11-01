# What do existing BLE P2P meshes teach us?

Type: research
Status: resolved

## Question

Survey bitchat, Briar, Bridgefy, Meshtastic, Berty/Wesh, Apple Multipeer Connectivity and Bluetooth SIG Mesh (incl. proxy/GATT bearer). For each: transport, discovery, routing/flooding, security, density behavior, documented failures and published evaluations. Verify whether SIG Mesh fits phone-to-phone meshes and why/why not. Output: cited comparison and lessons for MeshLink.

## Answer

Bluetooth SIG Mesh does not fit phone-to-phone meshes. iOS and Android apps cannot emit its advertising bearer. The spec (Table 3.13) lets only nodes that also support ADV act as Relay or Proxy. So phones are only GATT edge clients of a proxy, and there is no standard forwarding path between phones alone. Its shared NetKey with Provisioner admission also conflicts with per-installation identity. MeshLink needs its own connection-based BLE protocol; bitchat, Bridgefy and Berty all use dual-role GATT.

Best-documented designs to borrow:
- **Mesh broadcast:** managed flooding — seen-set dedup, relay jitter with cancel-on-duplicate, degree-adaptive TTL and fanout, split horizon, implicit ACK (bitchat, Meshtastic).
- **Directed messages:** learned next-hop with flood fallback (Meshtastic 2.6).
- **Store-and-forward:** bounded spray-and-wait with quotas (bitchat).

Recurring failures to design against:
- Unauthenticated relay or cache admission: bitchat CVE-2026-51376, Bridgefy impersonation.
- Shared-key "broadcast authenticity": Meshtastic, Bridgefy, SIG Mesh.
- Static or cleartext on-air identities and relay trails: bitchat, Bridgefy, Meshtastic.
- Compression bombs and side channels: Bridgefy.
- Cross-platform limit drift: bitchat #1618.
- Peer-negotiated encryption downgrade: Multipeer Connectivity, bitchat.
- Sybil eviction of the peer table: Meshtastic.

Briar uses Classic RFCOMM with contact-only sync, so it is not a mesh. Multipeer Connectivity is Apple-only, capped at 8 peers and foreground-only.

There is no published density evaluation of phone BLE meshes at 50–200 peers. SIG Mesh studies show broadcast storms and timing sensitivity, so flood parameters must come from simulation and a device lab.

Findings: [`docs/research/2026-10-31-prior-art-ble-meshes.md`](../../../docs/research/2026-10-31-prior-art-ble-meshes.md) (merged into `main` from `research/prior-art-ble-meshes` on 2026-11-01).
