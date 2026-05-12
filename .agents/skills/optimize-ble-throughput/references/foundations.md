<overview>
BLE throughput is controlled by airtime efficiency, not just the advertised PHY rate. This reference separates PHY bitrate, Link Layer payload throughput, ATT/L2CAP payload throughput, and application throughput.
</overview>

<sources>
Primary sources synthesized here:
- Memfault: `https://interrupt.memfault.com/blog/ble-throughput-primer`
- Novel Bits: `https://novelbits.io/bluetooth-5-speed-maximum-throughput/`
- Punch Through: `https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/`
- Punch Through FAQ: `https://punchthrough.com/ble-throughput-optimization-faq/`
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
- Link-layer encryption adds a 4-byte MIC and trims the ceiling only slightly

Common app-payload sweet spots:
- no DLE, default ATT MTU 23 -> 20 bytes
- DLE enabled and ATT MTU >= 247 -> 244 bytes in one full LL packet
- DLE enabled and ATT MTU >= 498 -> 495 bytes in two full LL packets
- otherwise -> `min(ATT_MTU - 3, stack_buffer_limit)` at the ATT layer, then account for how many LL packets that ATT PDU fragments into
</packet_math>

<ceilings>
Important ceilings from the sources:
- BLE 4.0, 27-byte LL payload: about **0.381 Mbps** raw LL payload throughput
- With DLE: about **0.803 Mbps** raw LL payload throughput
- With DLE + 2M PHY: about **1.434 Mbps** raw LL payload throughput
- Without DLE, many practical GATT paths top out around **54 kB/s** regardless of other tuning
- With careful tuning, application throughput can approach about **178 kB/s** on embedded-to-embedded links
- With a mobile device on one side, practical top-end throughput is often around **90-100 kB/s**

These are not application-throughput guarantees. Real application throughput is lower because of ATT or GATT overhead, empty packets, reverse-direction traffic, host delays, controller limits, retransmissions, and mobile-platform policy.
</ceilings>

<formulas>
Use the formula that matches the unit you actually measured:

- ATT-centric estimate:
  `throughput_bytes_per_second ≈ att_messages_per_event * app_bytes_per_att_message / connection_interval_seconds`
- LL-centric estimate:
  `throughput_bytes_per_second ≈ ll_packets_per_event * useful_app_bytes_per_ll_packet / connection_interval_seconds`

Important guardrail:
- never multiply **LL packets per event** by a **per-ATT-message** payload unless you already accounted for ATT fragmentation
- if you use 495-byte ATT chunks, model them as **two LL packets per ATT message** on a full DLE path
- if inputs are incomplete, present a range and label the assumptions explicitly
</formulas>

<interpretation>
Why 2M PHY is not 2x application throughput:
- TIFS does not shrink in classic BLE 5.x links
- empty packets and ACK behavior still consume airtime
- controller or host limits may cap packets per event
- reverse-direction traffic steals room from the data direction
- in practice, 2M PHY is often roughly **77% faster** than 1M for throughput-oriented transfers, not 100%

Why ATT MTU above 247 is not automatically faster:
- 247 ATT MTU is enough for the common **244-byte one-LL-packet** GATT path
- larger MTUs help only if the stack and application actually use larger ATT messages, such as the **495-byte two-LL-packet** sweet spot
- blindly raising ATT MTU without matching DLE, chunk sizing, and buffers may do nothing
</interpretation>

<security_and_directionality>
Important practical effects:
- link-layer encryption overhead is real but small: the 4-byte MIC drops an ideal **178 kB/s** ceiling only slightly, to roughly **176 kB/s**
- BLE is half-duplex: reverse-direction data, confirmations, or extra control traffic reduce throughput in the forward direction
- if both directions carry bulk traffic at once, each direction can end up far below the one-way ceiling even on a well-configured link
</security_and_directionality>

<illustrative_examples>
Examples from the source material:
- Novel Bits nRF52 example: 2M PHY + DLE + ATT MTU 247 + 7.5 ms interval + 5 packets per event -> about **1.3 Mbps** application throughput
- Novel Bits nRF52 example: 1M PHY + ATT MTU 23 + 7.5 ms interval + 11 packets per event -> about **235 kbps**
- Memfault example: using 12.5 ms instead of 11.25 ms can improve throughput when it fits an extra full packet exchange with no wasted tail time
- Punch Through FAQ: **495-byte** ATT payloads can approach about **177.8 kB/s** application throughput on a well-tuned embedded link because they fill two LL packets exactly
- Punch Through FAQ: on an ideal fast link, simultaneous bidirectional traffic can reduce each direction to roughly **103 kB/s**
</illustrative_examples>

<emerging_features>
Bluetooth 6.x adds newer timing features such as adjustable TIFS and short connection intervals. Treat these as hardware- and stack-dependent optimizations. Do not assume they exist unless the devices explicitly support them.
</emerging_features>
