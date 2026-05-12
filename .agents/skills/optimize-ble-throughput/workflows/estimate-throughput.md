<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
</required_reading>

<process>
1. Extract or ask for the minimum needed inputs: platform, role, direction, operation type, PHY, DLE state, ATT MTU, connection interval, and packets per connection event.
2. Determine the payload size at the correct layer:
   - default BLE 4.0 path: 20 app bytes per ATT packet
   - DLE + ATT MTU >= 247: up to 244 app bytes per ATT packet
   - otherwise use `min(ATT_MTU - 3, LL_payload - 7, stack buffer limit)`
3. Estimate one-direction application throughput with `packets_per_event * app_bytes_per_packet / connection_interval_seconds`, or the equivalent bits-per-second form if that is what the user asked for.
4. Compare the estimate against realistic ceilings from the references. Explain the gap using protocol overhead, TIFS, empty packets, reverse-direction traffic, controller limits, or platform policy.
5. If inputs are missing, provide a bounded range and label every assumption explicitly.
6. End with the best next lever to test: DLE, ATT MTU, 2M PHY, operation type, connection interval, packets per event, or buffering.
</process>

<success_criteria>
This workflow is complete when:
- the response names the throughput layer being estimated
- every assumption is explicit
- the estimate uses the right payload size and operation type
- the answer gives a realistic expected range, not only a spec maximum
</success_criteria>
