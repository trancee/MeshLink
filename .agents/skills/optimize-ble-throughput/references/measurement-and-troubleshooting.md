<overview>
Throughput debugging is easiest when you separate configuration, over-the-air behavior, host-stack behavior, and platform policy. Collect evidence in that order.
</overview>

<what_to_capture>
Capture these first:
- device models and OS versions
- which side is central
- negotiated ATT MTU
- DLE or LL length update state
- active PHY
- connection interval
- peripheral latency
- operation type
- packet size or chunk size at the application boundary
- packets per connection event
- measured throughput in bytes per second or kbps
- whether traffic is one-way or bidirectional
- number of simultaneous BLE connections
- whether advertising stays enabled during the transfer
- RSSI, distance, and interference conditions
</what_to_capture>

<expected_ranges>
Useful ballpark expectations:
- no DLE or default-style paths often land around **54 kB/s** or lower
- well-tuned embedded-to-embedded links can approach **178 kB/s**
- well-tuned links with a mobile device on one side are often around **90-100 kB/s**
- encryption overhead is only marginal; do not blame the MIC for a large throughput gap
</expected_ranges>

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
- whether ATT messages fragment across more LL packets than expected
</sniffers>

<symptom_mapping>
Common symptom -> likely cause:
- **Only 20-byte app payloads** -> default ATT MTU 23 path, no effective DLE benefit, or a stack path that still fragments early
- **About 182-byte writes on iOS** -> often expected on ATT MTU 185 CoreBluetooth paths
- **Throughput capped around ~54 kB/s** -> no effective DLE, still using request/indication-style operations, default MTU behavior, or very poor packets-per-event utilization
- **244-byte or 495-byte chunks but low kbps** -> too few packets per event, connection interval mismatch, queue starvation, reverse traffic, or mobile airtime limits
- **About one useful message every one or two connection events** -> write requests, indications, or application stop-and-wait behavior
- **2M PHY requested but no speed gain** -> still on 1M, not enough packets per event, or retries offset the PHY gain
- **Great lab results, weak field results** -> interference, range, or 2M fragility
- **Throughput collapses when bidirectional traffic begins** -> both directions are consuming the same half-duplex airtime
- **Great single-link results, poor multi-link or advertising-on results** -> radio scheduling is splitting airtime across other BLE work
</symptom_mapping>

<host_stack_warning>
BLE Link Layer reliability does not guarantee end-to-end application delivery. Some stacks still drop or stall because of buffer exhaustion, scheduling delays, or host-side throttling. If the sniffer looks healthy but the app rate is poor, inspect queue depth, pacing, callback timing, segmentation strategy, and whether the transmit pipeline stays full through the whole event.
</host_stack_warning>

<debug_plan>
Recommended debug sequence:
1. confirm negotiated parameters
2. confirm the transfer primitive: notification, indication, write without response, or write with response
3. confirm payload or chunk size at the app boundary and how it fragments on the air
4. confirm packets per event and queue depth
5. compare one-way vs two-way traffic
6. compare one connection vs multiple connections and advertising on vs off
7. compare 1M vs 2M under the same RF conditions
8. compare a 15 ms interval with one slightly shorter and one slightly longer value
9. repeat in a cleaner RF environment
</debug_plan>
