---
name: optimize-ble-throughput
description: Analyze and maximize Bluetooth Low Energy transfer speed across iOS, Android, and embedded links. Use when estimating BLE bandwidth, tuning MTU, DLE, PHY, or connection interval, debugging slow OTA or file transfer, or explaining why BLE throughput is below expectations.
metadata:
  created-at: '2026-05-11T00:00:00Z'
  version: '1.0.0'
  model: 'gpt-5'
  source-links:
    - 'https://interrupt.memfault.com/blog/ble-throughput-primer'
    - 'https://novelbits.io/bluetooth-5-speed-maximum-throughput/'
    - 'https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/'
    - 'https://argenox.com/blog/bluetooth-le-throughput-max-performance'
---

<objective>
Analyze Bluetooth Low Energy throughput from PHY to application payload, then recommend the highest-impact changes for the user's platform and traffic pattern. This skill is for performance work: estimating ceilings, diagnosing bottlenecks, and designing bulk-transfer protocols that respect BLE's real limits.
</objective>

<essential_principles>
- Never equate 1M or 2M PHY with application throughput.
- Separate these layers explicitly: PHY rate, Link Layer payload, ATT/L2CAP payload, and application throughput.
- First identify what is actually limiting the link: packet size, packets per connection event, connection interval, operation type, controller buffers, reverse-direction traffic, interference, or OS policy.
- Prefer measured evidence over intuition. If the user is debugging a real link, ask for negotiated MTU, PHY, DLE state, connection interval, packets per event, device and OS, and if possible a sniffer trace.
- Optimize throughput and latency separately. Lower connection interval improves latency, but not always maximum throughput.
- For bulk transfer, prefer streaming primitives: notifications, write without response, or L2CAP CoC when available.
- Treat iOS and Android behavior as empirical platform policy, not guaranteed spec behavior.
</essential_principles>

<routing>
Route directly from the user's request:

- Estimate, calculate, ceiling, bandwidth, or "how fast can BLE go" -> `workflows/estimate-throughput.md`
- Maximize, improve, tune, or speed up OTA, log, or file transfer -> `workflows/optimize-link.md`
- Debug, audit, sniff, or "why am I only getting X kbps" -> `workflows/audit-slow-link.md`
- Design chunking, framing, ACK strategy, OTA transport, log upload, file transfer, or GATT vs L2CAP CoC -> `workflows/design-transfer-protocol.md`

If the user mixes calculation and tuning, estimate first, then optimize.
If critical inputs are missing, ask only for the minimum missing measurements.
</routing>

<quick_start>
For a quick first-pass estimate, gather:

- platform and role: iOS, Android, or embedded; central or peripheral
- PHY: 1M or 2M
- DLE: enabled or not
- ATT MTU
- connection interval
- packets per connection event
- operation type: notification, indication, write without response, or write with response

Then compute one-direction application throughput as:

`throughput_bytes_per_second ≈ packets_per_event * app_bytes_per_packet / connection_interval_seconds`

where `app_bytes_per_packet = min(ATT_MTU - 3, LL_payload - 7, stack_buffer_limit)` for a single ATT PDU inside one LL packet, and `connection_interval_seconds` is the connection interval expressed in seconds.

Useful ceilings:
- BLE 4.0, 27-byte LL payload: ~0.381 Mbps raw LL throughput
- With DLE: ~0.803 Mbps raw LL throughput
- With DLE + 2M PHY: ~1.434 Mbps raw LL throughput
- Real application throughput on a well-tuned 2M link is often about 1.0-1.4 Mbps, not 2 Mbps
</quick_start>

<reference_index>
Read domain knowledge from `references/`:

- `references/foundations.md` — packet math, overhead, formulas, ceilings, and why PHY rate is not app throughput
- `references/mobile-platforms.md` — iOS, Android, and embedded-central constraints that change tuning advice
- `references/tuning-checklist.md` — ordered tuning sequence and anti-patterns
- `references/measurement-and-troubleshooting.md` — what to capture, how to use sniffers, and symptom-to-cause mapping
</reference_index>

<workflows_index>
- `workflows/estimate-throughput.md` — estimate or compare theoretical and practical throughput
- `workflows/optimize-link.md` — tune an existing BLE link for higher throughput
- `workflows/audit-slow-link.md` — diagnose why a real link is slower than expected
- `workflows/design-transfer-protocol.md` — design a bulk-transfer protocol for OTA, logs, and files
</workflows_index>

<success_criteria>
This skill is successful when it:
- states throughput at the correct layer
- identifies the current bottleneck instead of guessing
- gives platform-specific tuning guidance
- distinguishes theoretical ceilings from realistic measured ranges
- recommends a concrete next experiment or configuration change
</success_criteria>
