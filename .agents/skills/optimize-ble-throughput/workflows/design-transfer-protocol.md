<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/transfer-methods.md`
4. `references/tuning-checklist.md`
</required_reading>

<process>
1. Choose the transport based on direction, platform support, and bulk-data needs. Default to GATT notifications or write without response. Consider L2CAP CoC when GATT overhead or framing constraints are the bottleneck.
2. Derive chunk size from negotiated runtime limits, not hard-coded constants. Prefer **20 bytes** on the default path, **244 bytes** on the one-packet DLE path, and **495 bytes** on the two-packet DLE path when ATT MTU >= 498 and both ends can sustain it.
3. Because ATT operations are capped at **512 bytes**, segment larger objects at the application layer with sequence numbers, total length, integrity metadata as needed, and resume checkpoints.
4. Avoid stop-and-wait. Use a sliding window or queued burst model so the link stays saturated.
5. Separate reliability from pacing. BLE LL is reliable over the air, but host stacks can still drop under pressure. Use block or checkpoint acknowledgments rather than ACKing every chunk. If throughput matters more than symmetric exchange, design mostly one-way phases.
6. Design for parameter drift. Phones may change interval or PHY policy; 2M may need a 1M fallback when RF quality worsens. Some mobile APIs expose smaller runtime payload limits than the negotiated ATT MTU.
7. Reduce airtime competition during critical transfers: avoid extra BLE links, consider pausing advertising, and minimize reverse-direction traffic.
8. End with a validation plan: throughput target, acceptable loss behavior, resume semantics, fallback behavior, and the device matrix needed to prove the design.
</process>

<success_criteria>
This workflow is complete when:
- the protocol keeps the BLE link saturated without per-chunk stalls
- reliability strategy does not destroy throughput
- chunk sizing is tied to negotiated runtime limits
- the design accounts for half-duplex and mobile-platform constraints
- the design includes a concrete validation matrix
</success_criteria>
