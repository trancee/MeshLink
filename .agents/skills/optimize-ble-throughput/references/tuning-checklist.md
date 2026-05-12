<overview>
Use this checklist bottom-up. Change one lever at a time and re-measure after each change.
</overview>

<ordered_checklist>
1. **Name the target**
   - desired throughput
   - acceptable latency
   - direction: central -> peripheral, peripheral -> central, or both
   - platform mix: embedded/embedded, phone/peripheral, or multi-link

2. **Pick the right transfer primitive**
   - bulk upload/download: notifications or write without response
   - avoid indications, write requests, and stop-and-wait application ACKs unless reliability semantics require them

3. **Enable DLE**
   - verify that LL length update actually happened
   - if payloads stay at 20 bytes, do not assume DLE is helping
   - without DLE, many paths top out around 54 kB/s no matter what else you tune

4. **Set ATT MTU and chunk size together**
   - use at least 247 ATT MTU for the common 244-byte one-packet DLE path
   - use ATT MTU 498 or higher only when the stack and application will actually exploit 495-byte two-packet chunks
   - on mobile, trust the runtime-reported payload limit over a requested maximum

5. **Use 2M PHY when RF allows**
   - good for speed and radio-on time
   - bad choice on weak or noisy links where retries dominate
   - remember that 2M is usually about 77% faster than 1M for throughput, not 100%

6. **Tune connection interval and peripheral latency for utilization, not dogma**
   - low interval helps latency
   - for mobile throughput work, start around 15 ms and compare nearby values on the exact device pair
   - do not assume 7.5 ms beats 15 ms in real throughput
   - set peripheral latency to 0 when central-to-peripheral throughput matters

7. **Maximize packets per connection event and keep the pipeline fed**
   - increase controller and host buffers if the stack allows it
   - keep the transmit queue full
   - verify the real packet count with logs or a sniffer
   - if the queue drains before the event ends, that airtime is gone

8. **Reduce airtime competition**
   - minimize reverse-direction traffic during a one-way bulk transfer
   - avoid multiple BLE connections while measuring peak throughput
   - disable advertising during the transfer if the product can tolerate it

9. **Consider L2CAP CoC**
   - use when GATT overhead, ATT-operation semantics, or custom framing becomes the bottleneck

10. **Retest in realistic RF conditions**
    - interference and retries can erase lab gains
</ordered_checklist>

<anti_patterns>
Avoid these mistakes:
- quoting 1 Mbps or 2 Mbps as application throughput
- raising ATT MTU while leaving DLE, chunk size, or queue depth unchanged
- using arbitrary chunk sizes when 244 or 495 would pack better
- using per-chunk application ACKs for bulk transfer
- assuming 2M PHY is always faster in the field
- assuming the phone honored the requested interval or PHY without measuring
- optimizing MTU and PHY while the real bottleneck is queue starvation, reverse traffic, extra connections, or advertising airtime
</anti_patterns>
