<overview>
Use this checklist bottom-up. Change one lever at a time and re-measure after each change.
</overview>

<ordered_checklist>
1. **Name the target**
   - desired throughput
   - acceptable latency
   - direction: central -> peripheral or peripheral -> central

2. **Pick the right GATT operation**
   - bulk upload/download: notifications or write without response
   - avoid indications and write requests for high-rate streaming unless required

3. **Enable DLE**
   - verify that LL length update actually happened
   - if payloads stay at 20 bytes, do not assume DLE is helping

4. **Set ATT MTU high enough**
   - at least 247 for the common 244-byte single-PDU GATT path
   - larger values only help when the stack path actually uses them

5. **Use 2M PHY when RF allows**
   - good for speed and radio-on time
   - bad choice on weak or noisy links where retries dominate

6. **Tune connection interval for utilization, not dogma**
   - low interval helps latency
   - best throughput comes from fitting more full packet exchanges, not from the smallest number on paper

7. **Maximize packets per connection event**
   - increase controller and host buffers if the stack allows it
   - keep the transmit queue full
   - verify the real packet count with logs or a sniffer

8. **Minimize reverse-direction traffic during bulk transfer**
   - empty responses already consume airtime
   - additional data in the other direction reduces throughput further

9. **Consider L2CAP CoC**
   - use when GATT overhead or framing becomes the bottleneck

10. **Retest in realistic RF conditions**
    - interference and retries can erase lab gains
</ordered_checklist>

<anti_patterns>
Avoid these mistakes:
- quoting 1 Mbps or 2 Mbps as application throughput
- raising ATT MTU while leaving DLE or packet length unchanged
- using per-chunk application ACKs for bulk transfer
- assuming 2M PHY is always faster in the field
- assuming the phone honored the requested interval or PHY without measuring
- optimizing MTU and PHY while the real bottleneck is queue starvation or packets per event
</anti_patterns>
