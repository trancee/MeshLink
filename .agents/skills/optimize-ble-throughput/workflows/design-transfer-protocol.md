<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/tuning-checklist.md`
</required_reading>

<process>
1. Choose the transport based on direction, platform support, and bulk-data needs. Default to GATT notifications or write without response. Consider L2CAP CoC when GATT overhead or framing constraints are the bottleneck.
2. Derive chunk size from negotiated runtime limits, not hard-coded constants. For common GATT single-PDU paths the practical payload is usually 20 bytes without DLE and up to 244 bytes with DLE plus ATT MTU >= 247.
3. Avoid stop-and-wait. Use sequencing plus a sliding window or queued burst model so the link stays saturated.
4. Separate reliability from pacing. BLE LL is reliable over the air, but host stacks can still drop under pressure. Use sequence numbers and retransmit at block or checkpoint granularity rather than ACKing every chunk.
5. Design for parameter drift. Phones may change interval or PHY policy; 2M may need a 1M fallback when RF quality worsens.
6. End with a validation plan: throughput target, acceptable loss behavior, resume semantics, and the device matrix needed to prove the design.
</process>

<success_criteria>
This workflow is complete when:
- the protocol keeps the BLE link saturated without per-chunk stalls
- reliability strategy does not destroy throughput
- chunk sizing is tied to negotiated runtime limits
- the design includes a concrete validation matrix
</success_criteria>
