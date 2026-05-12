<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/measurement-and-troubleshooting.md`
4. `references/tuning-checklist.md`
</required_reading>

<process>
1. Gather evidence before theorizing: measured throughput, device and OS, central/peripheral roles, negotiated ATT MTU, PHY, DLE state, connection interval, packet size, directionality, RSSI or field conditions, and any logs or sniffer traces.
2. Classify the symptom:
   - only 20-byte payloads -> default ATT path or no DLE benefit reaching the app
   - around 182 bytes on iOS writes -> often expected CoreBluetooth payload on ATT MTU 185 paths
   - 244-byte packets but low kbps -> packets per event, buffering, connection interval, or reverse traffic
   - 2M requested but no speedup -> still on 1M, too few packets per event, or RF conditions are poor
   - bench results good but field results poor -> retransmissions, interference, or 2M range limits
3. Use logs or a sniffer to verify MTU exchange, LL length update, PHY update, and packets per connection event.
4. Check for host-side throttling or drops: queue starvation, application stop-and-wait behavior, slow processing between events, insufficient buffers, or stack-specific operation limits.
5. Name the most likely root cause, cite the evidence, and propose the next single experiment that would confirm or disprove it.
</process>

<success_criteria>
This workflow is complete when:
- the response points to a specific limiting factor
- the diagnosis is evidence-based, not speculative
- the next test is narrow enough to confirm the hypothesis
- the user knows what measurement to collect if evidence is still missing
</success_criteria>
