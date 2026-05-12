<overview>
Throughput debugging is easiest when you separate configuration, over-the-air behavior, and host-stack behavior. Collect evidence in that order.
</overview>

<what_to_capture>
Capture these first:
- device models and OS versions
- which side is central
- negotiated ATT MTU
- DLE or LL length update state
- active PHY
- connection interval
- operation type
- packet size at the application boundary
- measured throughput in bytes per second or kbps
- RSSI, distance, and interference conditions
</what_to_capture>

<sniffers>
Useful tools called out in the sources:
- Ellisys Bluetooth Explorer
- Frontline Sodera LE
- Nordic BLE Sniffer as a lower-cost option

If a sniffer is available, inspect:
- LL length request and response
- PHY update procedure
- MTU exchange
- packets per connection event
- retransmissions or failed packets
</sniffers>

<symptom_mapping>
Common symptom -> likely cause:
- **Only 20-byte app payloads** -> default ATT MTU 23 path, no effective DLE benefit, or a stack path that still fragments early
- **About 182-byte writes on iOS** -> often expected on ATT MTU 185 CoreBluetooth paths
- **244-byte payloads but low kbps** -> too few packets per event, connection interval mismatch, queue starvation, or reverse traffic
- **2M PHY requested but no speed gain** -> still on 1M, not enough packets per event, or retries offset the PHY gain
- **Great lab results, weak field results** -> interference, range, or 2M fragility
- **Throughput collapses when bidirectional traffic begins** -> both directions are consuming the same connection-event airtime
</symptom_mapping>

<host_stack_warning>
BLE Link Layer reliability does not guarantee end-to-end application delivery. Some stacks still drop or stall because of buffer exhaustion, scheduling delays, or host-side throttling. If the sniffer looks healthy but the app rate is poor, inspect queue depth, pacing, and callback timing.
</host_stack_warning>

<debug_plan>
Recommended debug sequence:
1. confirm negotiated parameters
2. confirm payload size at the app boundary
3. confirm packets per event
4. compare one-way vs two-way traffic
5. compare 1M vs 2M under the same RF conditions
6. compare short and slightly longer connection intervals
7. repeat in a cleaner RF environment
</debug_plan>
