<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/transfer-methods.md`
4. `references/tuning-checklist.md`
5. `references/measurement-and-troubleshooting.md`
</required_reading>

<process>
1. Establish the baseline: measured throughput, platform and role, PHY, DLE, ATT MTU, connection interval, peripheral latency, packets per event, packet or chunk size, direction, operation type, number of simultaneous connections, advertising state, and RF conditions.
2. Fix the transfer primitive first. For bulk data, prefer notifications or write without response. Avoid indications, write requests, and per-chunk application ACKs unless the user explicitly optimizes for reliability over speed. Make sure the transmit queue stays full.
3. Reduce per-packet overhead. Enable DLE. Set ATT MTU high enough and pair it with the right chunk size: **244-byte** chunks are the default DLE sweet spot, while **495-byte** chunks help only when ATT MTU >= 498 and the stack path can actually sustain them. On iOS, rely on runtime limits instead of trying to request MTU directly.
4. Increase radio efficiency. Request 2M PHY when both sides support it and signal quality is good. If the link is marginal, keep or fall back to 1M.
5. Tune connection-event utilization. For mobile throughput work, start around **15 ms**, compare nearby interval values on the exact hardware pair, set peripheral latency to **0** when central-to-peripheral throughput matters, maximize packets per event, and keep controller and host queues full.
6. Remove airtime competition. Minimize reverse-direction traffic during a one-way transfer, avoid multiple BLE connections while chasing peak throughput, and disable advertising during the transfer if the product allows it.
7. Call out what the phone controls. Mobile centrals often decide the final interval, event length, and PHY behavior, so distinguish what can be requested from what can be guaranteed.
8. If GATT is still the bottleneck, recommend L2CAP CoC or a protocol redesign.
9. Finish with a short before-and-after test plan and the metric to re-measure.
</process>

<success_criteria>
This workflow is complete when:
- the highest-impact bottleneck is identified
- recommendations are ordered by expected benefit
- mobile-platform constraints are called out explicitly
- transfer primitive and chunk sizing are part of the tuning advice
- the user gets a concrete next experiment with a measurable outcome
</success_criteria>
