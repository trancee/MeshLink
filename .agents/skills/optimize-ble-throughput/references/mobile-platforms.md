<overview>
Platform policy changes what can actually be tuned. The phone is often the BLE central, which means many parameters can be requested by the accessory but not guaranteed.
</overview>

<ios>
Key iOS constraints and patterns:
- CoreBluetooth does not expose a public API to request a specific ATT MTU. Use runtime queries such as `maximumWriteValueLength(for:)`.
- Modern iPhones generally support 2M PHY and often prefer it when both sides support it, but the central still makes the final choice.
- Treat roughly **15 ms** as the practical throughput floor and common sweet spot for generic iOS peripherals. Do not assume a lower requested interval will be honored.
- Practical iOS write-without-response payloads are often around **182 bytes** on ATT MTU 185 paths. Use the runtime-reported limit, not a hard-coded constant.
- Observed iOS behavior may allocate only part of a connection interval to BLE. Single-connection tests around 15 ms have shown roughly half the interval used for BLE airtime. Treat this as empirical behavior, not a spec guarantee.
- iOS supports L2CAP CoC from iOS 11 onward.
- Always optimize the peripheral side around what the phone negotiates, not what the accessory wishes for.
</ios>

<android>
Key Android constraints and patterns:
- Android often allows **7.5 ms** intervals, but many devices show little or no throughput gain below about **15 ms**. Behavior varies by device, firmware, and power policy.
- `requestMtu(517)` can be used, but the negotiated value is what matters.
- Android controllers and stacks vary widely in packets-per-event limits. Older guidance cited around 6 packets per event on some phones; do not assume a universal number.
- Android also exposes L2CAP CoC APIs on modern releases.
- Airtime allocation, coexistence with Wi-Fi, and scheduler behavior vary significantly across vendors.
- App code must serialize GATT operations and keep queues full, or host-side behavior becomes the bottleneck.
</android>

<embedded_central>
When you control the central side on embedded hardware, throughput tuning is easier:
- you can request the connection interval you actually want
- you can aggressively manage buffer depth and packets per event
- you can choose when to switch PHYs
- you can validate behavior on both ends with the same stack or logging strategy
- this is usually the easiest environment for reaching the top end of BLE throughput, including the ~**178 kB/s** class of results
</embedded_central>

<platform_policy_effects>
Platform policy often matters more than one extra tuning knob:
- mobile OSes may allocate only part of a connection interval to BLE
- multiple simultaneous BLE connections divide radio time and can shorten effective connection events
- continued advertising while connected can also steal airtime from a bulk transfer
- if a phone is central, distinguish carefully between requested parameters and negotiated behavior on the air
</platform_policy_effects>

<cross_platform_rules>
Cross-platform rules that stay true:
- prefer notifications or write without response for bulk transfer
- enable DLE whenever both sides support it
- treat 2M PHY as a speed-versus-range tradeoff
- for general throughput work, start near **15 ms** and compare with one slightly shorter and one slightly longer interval on the exact hardware pair
- set peripheral latency to **0** when central-to-peripheral throughput matters
- measure actual packets per event instead of assuming them
- if the phone is central, distinguish between requested parameters and negotiated parameters
- avoid extra BLE connections and disable advertising during critical transfers when possible
</cross_platform_rules>

<historical_caution>
Some mobile-specific numbers in older articles are historically useful but not universal on current devices. Use them as starting expectations, then verify on the exact OS and hardware pair in front of you.
</historical_caution>
