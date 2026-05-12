<overview>
Once PHY, DLE, and ATT MTU are reasonable, transfer method and chunking usually determine whether a BLE link reaches its ceiling. This reference covers the application-layer choices that most often decide the outcome.
</overview>

<operation_choices>
Fast bulk-transfer primitives:
- **Write Command** is faster than **Write Request** because it is asynchronous and can be queued back-to-back
- **Notification** is faster than **Indication** for the same reason
- **Write Request** and **Indication** require a response or confirmation before the next operation can advance, which often limits the path to about one ATT operation every one or two connection events

Use request-style operations only when you truly need synchronous acknowledgment semantics. For throughput-focused designs, default to write without response or notifications.
</operation_choices>

<chunk_sizing>
Chunk-size rules that matter in practice:
- ATT operations are capped at **512 bytes**, so large objects must be segmented at the application layer
- On a DLE-enabled GATT path, **244 bytes** is the one-packet sweet spot because `251 - 4 - 3 = 244`
- On a DLE-enabled path with ATT MTU >= **498**, **495 bytes** is the two-packet sweet spot because `495 + 3 + 4 = 502 = 2 * 251`
- Sending **496 bytes** instead of 495 forces a third LL packet carrying only one additional byte of useful data and can drop theoretical application throughput from about **177.8 kB/s** to about **156.2 kB/s**
- Many mobile stacks expose smaller runtime payload limits than the theoretical ATT MTU path. On iOS, write-without-response payloads are often around **182 bytes** on ATT MTU 185 paths. Always use negotiated runtime limits.
</chunk_sizing>

<streaming_patterns>
Patterns that keep the link saturated:
- segment large transfers with sequence numbers, total length, and optional checksum or block metadata
- use a **sliding window** or queued-burst model rather than stop-and-wait
- keep the transmit queue fed for the whole connection event; if the queue empties early, that airtime is lost
- ACK by block or checkpoint rather than every chunk
- when the application can tolerate it, structure the transfer into mostly one-way phases instead of symmetric back-and-forth chatter
</streaming_patterns>

<bidirectional_and_multilink_tradeoffs>
Common airtime thieves:
- BLE is half-duplex, so simultaneous bulk traffic in both directions cuts the one-way ceiling substantially
- On an ideal fast link, two-way bulk transfer can reduce each direction to roughly **103 kB/s**
- Multiple BLE connections divide radio time further and make packets-per-event less predictable
- Advertising while connected can also consume airtime that would otherwise go to the data transfer
- Peripheral latency should be **0** when central-to-peripheral throughput matters; it does not reduce peripheral-to-central send opportunities the same way
</bidirectional_and_multilink_tradeoffs>

<selection_guide>
Practical selection guide:
- **fastest common GATT path** -> notifications or write commands, DLE enabled, ATT MTU >= 247, 244-byte or 495-byte chunks, full transmit queue
- **mobile-friendly path** -> notifications or write commands sized to the runtime-reported limit rather than a hard-coded maximum
- **GATT framing is the bottleneck** -> consider L2CAP CoC if both platforms support it and the implementation complexity is acceptable
</selection_guide>
