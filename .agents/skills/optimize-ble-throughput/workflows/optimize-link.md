<required_reading>
Read these references now:
1. `references/foundations.md`
2. `references/mobile-platforms.md`
3. `references/tuning-checklist.md`
4. `references/measurement-and-troubleshooting.md`
</required_reading>

<process>
1. Establish the baseline: measured throughput, platform and role, PHY, DLE, ATT MTU, connection interval, packets per event, packet size, direction, operation type, and RF conditions.
2. Fix the transfer primitive first. For bulk data, prefer notifications or write without response. Avoid indications, write requests, and stop-and-wait application ACKs unless the user explicitly optimizes for reliability over speed.
3. Reduce per-packet overhead. Enable DLE. Set ATT MTU to at least 247 when the stack allows it so a 244-byte GATT payload can fit in one LL packet. On iOS, rely on negotiated runtime limits instead of trying to request MTU directly.
4. Increase radio efficiency. Request 2M PHY when both sides support it and signal quality is good. If the link is marginal, keep or fall back to 1M.
5. Tune connection-event utilization. Choose a connection interval that actually fits more full packet exchanges, keep controller and host queues full, and minimize reverse-direction traffic during a one-way bulk transfer.
6. Call out what the phone controls. Mobile centrals often decide the final interval and PHY, so distinguish what can be requested from what can be guaranteed.
7. If GATT is still the bottleneck, recommend L2CAP CoC or a protocol redesign.
8. Finish with a short before-and-after test plan and the metric to re-measure.
</process>

<success_criteria>
This workflow is complete when:
- the highest-impact bottleneck is identified
- recommendations are ordered by expected benefit
- mobile-platform constraints are called out explicitly
- the user gets a concrete next experiment with a measurable outcome
</success_criteria>
