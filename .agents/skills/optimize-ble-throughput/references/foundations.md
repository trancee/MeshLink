<overview>
BLE throughput is controlled by airtime efficiency, not just the advertised PHY rate. This reference separates PHY bitrate, Link Layer payload throughput, ATT payload throughput, and application throughput.
</overview>

<sources>
Primary sources synthesized here:
- Memfault: `https://interrupt.memfault.com/blog/ble-throughput-primer`
- Novel Bits: `https://novelbits.io/bluetooth-5-speed-maximum-throughput/`
- Punch Through: `https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/`
- Argenox: `https://argenox.com/blog/bluetooth-le-throughput-max-performance`
</sources>

<packet_math>
Useful terms:
- **Connection event**: one scheduled burst where central and peripheral exchange one or more packets
- **Connection interval**: time between connection events
- **TIFS / IFS**: mandatory gap between packets, traditionally 150 microseconds
- **DLE**: Data Length Extension, which raises LL payload size from 27 to 251 bytes

Common overheads on a GATT data path:
- Link Layer payload: 27 bytes without DLE, up to 251 bytes with DLE
- L2CAP header: 4 bytes
- ATT header: 3 bytes
- Maximum single-ATT-packet app payload: `LL_payload - 7`

That yields these common app payload sizes:
- no DLE, default ATT MTU 23 -> 20 bytes
- DLE enabled and ATT MTU >= 247 -> 244 bytes
- otherwise -> `min(ATT_MTU - 3, LL_payload - 7, stack buffer limit)`
</packet_math>

<ceilings>
Important ceilings from the sources:
- BLE 4.0, 27-byte LL payload: about **0.381 Mbps** raw LL payload throughput
- With DLE: about **0.803 Mbps** raw LL payload throughput
- With DLE + 2M PHY: about **1.434 Mbps** raw LL payload throughput

These are not application-throughput guarantees. Real application throughput is lower because of ATT or GATT overhead, empty packets, reverse-direction traffic, host delays, controller limits, and retransmissions.
</ceilings>

<formulas>
Quick one-direction application estimate:

`throughput_bytes_per_second ≈ packets_per_event * app_bytes_per_packet / connection_interval_seconds`

Equivalent bits-per-second form:

`throughput_bps ≈ packets_per_event * app_bytes_per_packet * 8 / connection_interval_seconds`

Use this only after naming the assumptions:
- one direction or both directions?
- notifications or writes without response, vs operations that require replies?
- actual packets per event supported by the devices?
- does the stack keep buffers full?
</formulas>

<interpretation>
Why 2M PHY is not 2x application throughput:
- TIFS does not shrink in classic BLE 5.x links
- empty packets and ACK behavior still consume airtime
- controller or host limits may cap packets per event
- reverse-direction traffic steals room from the data direction

Why ATT MTU above 247 is not a magic speed button:
- for a single ATT PDU carried in one max-sized LL packet, 247 ATT MTU already reaches the 244-byte app-payload ceiling
- larger MTUs can still help specific stack paths, long values, or fragmentation behavior, but they do not increase the single-PDU GATT payload past 244 bytes
</interpretation>

<illustrative_examples>
Examples from the source material:
- Novel Bits nRF52 example: 2M PHY + DLE + ATT MTU 247 + 7.5 ms interval + 5 packets per event -> about **1.3 Mbps** application throughput
- Novel Bits nRF52 example: 1M PHY + ATT MTU 23 + 7.5 ms interval + 11 packets per event -> about **235 kbps**
- Memfault example: using 12.5 ms instead of 11.25 ms can improve throughput when it fits an extra full packet exchange with no wasted tail time
</illustrative_examples>

<emerging_features>
Bluetooth 6.x adds newer timing features such as adjustable TIFS and short connection intervals. Treat these as hardware- and stack-dependent optimizations. Do not assume they exist unless the devices explicitly support them.
</emerging_features>
