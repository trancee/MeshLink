<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/transfer-methods.md`
</required_reading>

<process>
1. Extract or ask for the minimum needed inputs: platform, role, direction, operation type, whether traffic is one-way or bidirectional, PHY, DLE state, ATT MTU, connection interval, packets per event, and whether extra connections or advertising are active.
2. Determine the useful payload size at the correct layer:
   - default BLE 4.0 path -> **20** app bytes per ATT message
   - DLE + ATT MTU >= 247 -> **244** app bytes for the one-packet GATT path
   - DLE + ATT MTU >= 498 with intentionally sized large ATT messages -> **495** app bytes for the two-packet GATT path
   - otherwise use the actual ATT payload size and account for how many LL packets it fragments into
3. Estimate with the unit the user or sniffer actually measured:
   - `att_messages_per_event * app_bytes_per_att_message / connection_interval_seconds`
   - or `ll_packets_per_event * useful_app_bytes_per_ll_packet / connection_interval_seconds`
   - never mix LL-packet counts with per-ATT-message payload sizes unless the fragmentation overhead has already been amortized
4. Compare the estimate against realistic ceilings from the references. Call out important anchors: about **54 kB/s** without DLE, about **178 kB/s** for a well-tuned embedded link, and about **90-100 kB/s** when a mobile device is on one side.
5. Explain the gap using protocol overhead, TIFS, empty packets, reverse-direction traffic, controller limits, platform policy, extra connections, or advertising airtime.
6. If inputs are missing, provide a bounded range and label every assumption explicitly.
7. End with the best next lever to test: DLE, ATT MTU, chunk size, 2M PHY, transfer primitive, connection interval, packets per event, or buffering.
</process>

<success_criteria>
This workflow is complete when:
- the response names the throughput layer being estimated
- every assumption is explicit
- the estimate uses the right payload size and operation type
- the answer does not mix ATT-message and LL-packet units incorrectly
- the answer gives a realistic expected range, not only a spec maximum
</success_criteria>
