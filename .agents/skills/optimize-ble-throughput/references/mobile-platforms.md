<overview>
Platform policy changes what can actually be tuned. The phone is often the BLE central, which means many parameters can be requested by the accessory but not guaranteed.
</overview>

<ios>
Key iOS constraints and patterns:
- CoreBluetooth does not expose a public API to request a specific ATT MTU. Use runtime queries such as `maximumWriteValueLength(for:)`.
- Modern iPhones generally support 2M PHY and often prefer it when both sides support it, but the central still makes the final choice.
- Practical iOS write-without-response payloads are often around **182 bytes** on ATT MTU 185 paths.
- Historical guidance suggests roughly **15 ms** short-duration intervals, **30 ms** more sustainable intervals, and **11.25 ms** for HID or input-device cases. Treat these as empirical, not universal.
- iOS supports L2CAP CoC from iOS 11 onward.
- Always optimize the peripheral side around what the phone negotiates, not what the accessory wishes for.
</ios>

<android>
Key Android constraints and patterns:
- Android often allows **7.5 ms** intervals, but behavior varies by device, firmware, and power policy.
- `requestMtu(517)` can be used, but the negotiated value is what matters.
- Android controllers and stacks vary widely in packets-per-event limits. Older guidance cited around 6 packets per event on some phones; do not assume a universal number.
- Android also exposes L2CAP CoC APIs on modern releases.
- App code must serialize GATT operations and keep queues full, or host-side behavior becomes the bottleneck.
</android>

<embedded_central>
When you control the central side on embedded hardware, throughput tuning is easier:
- you can request the connection interval you actually want
- you can aggressively manage buffer depth and packets per event
- you can choose when to switch PHYs
- you can validate behavior on both ends with the same stack or logging strategy

This is usually the easiest environment for reaching the top end of BLE throughput.
</embedded_central>

<cross_platform_rules>
Cross-platform rules that stay true:
- prefer notifications or write without response for bulk transfer
- enable DLE whenever both sides support it
- treat 2M PHY as a speed-versus-range tradeoff
- measure actual packets per event instead of assuming them
- if the phone is central, distinguish between requested parameters and negotiated parameters
</cross_platform_rules>

<historical_caution>
Some mobile-specific numbers in older articles are historically useful but not universal on current devices. Use them as starting expectations, then verify on the exact OS and hardware pair in front of you.
</historical_caution>
