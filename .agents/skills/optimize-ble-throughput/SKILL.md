---
name: optimize-ble-throughput
description: Use this skill when a BLE prompt is about transfer performance rather than scanning, bonding, permissions, or generic transport code. It specializes in estimating, diagnosing, and improving OTA, log, and file-transfer speed across iOS, Android, and embedded links. Best for MTU, DLE, PHY, packets-per-event, and connection-interval math; ATT vs LL fragmentation; 182-byte iOS write limits; 244/495-byte chunk sizing; notifications vs indications; write commands vs write requests; ACK strategy; sniffer-based diagnosis; and explaining why a link is only achieving a given kB/s.
metadata:
  created-at: '2026-05-11T00:00:00Z'
  updated-at: '2026-05-12T00:00:00Z'
  version: '1.1.2'
  model: 'gpt-5'
  source-links:
    - 'https://interrupt.memfault.com/blog/ble-throughput-primer'
    - 'https://novelbits.io/bluetooth-5-speed-maximum-throughput/'
    - 'https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/'
    - 'https://punchthrough.com/ble-throughput-optimization-faq/'
    - 'https://argenox.com/blog/bluetooth-le-throughput-max-performance'
  changelog:
    - date: '2026-05-12T00:00:00Z'
      version: '1.1.2'
      model: 'gpt-5'
      source: 'iterative local trigger-eval experiments'
      summary: 'Rewrote the description in imperative form and sharpened it around throughput analysis, transfer-performance prompts, ATT vs LL math, chunk sizing, ACK strategy, and non-code BLE optimization work.'
      reason: 'Try to make the skill more distinctive versus generic BLE implementation skills and more aligned with the trigger eval prompts.'
    - date: '2026-05-12T00:00:00Z'
      version: '1.1.1'
      model: 'gpt-5'
      source: 'local trigger evals + optimize-ble-throughput/evals/trigger-evals.json'
      summary: 'Added small trigger and task eval sets and tightened the description to cover bulk transfer, transfer primitives, chunk sizing, packets per event, and peripheral latency.'
      reason: 'Make the skill easier to regression-test and improve triggering for realistic BLE throughput prompts.'
    - date: '2026-05-12T00:00:00Z'
      version: '1.1.0'
      model: 'gpt-5'
      source: 'https://punchthrough.com/ble-throughput-optimization-faq/'
      summary: 'Added practical throughput ranges, 244/495-byte payload guidance, 15 ms interval guidance, peripheral-latency and multi-connection advice, and stronger application-layer troubleshooting.'
      reason: 'Refresh the skill with newer BLE throughput FAQ guidance and more actionable tuning advice.'
---

<objective>
Analyze BLE throughput from PHY to application framing, then recommend the highest-impact changes for the user's platform and traffic pattern. This skill is for performance work: estimating ceilings, diagnosing bottlenecks, and designing bulk-transfer protocols that respect BLE's real limits on phones and embedded devices.
</objective>

<essential_principles>
- Never equate 1M or 2M PHY with application throughput.
- Separate these layers explicitly: PHY rate, Link Layer payload, ATT/L2CAP payload, and application throughput.
- DLE, ATT MTU, and application chunk size must line up. Enabling only one of them rarely moves real throughput.
- Treat **244 bytes** and **495 bytes** as important GATT payload sweet spots on DLE-enabled links. Crossing 244 or 495 by one byte can force another LL packet and reduce efficiency sharply.
- BLE is half-duplex. Reverse-direction traffic, indications, write requests, extra connections, and even ongoing advertising all steal airtime from the fast direction.
- First identify what is actually limiting the link: packet size, packets per connection event, connection interval, operation type, controller buffers, reverse-direction traffic, interference, or OS policy.
- Prefer measured evidence over intuition. If the user is debugging a real link, ask for negotiated MTU, PHY, DLE state, connection interval, packets per event, peripheral latency, device and OS, and if possible a sniffer trace.
- Optimize throughput and latency separately. On mobile links, the minimum interval is not automatically the fastest interval; around **15 ms** is often the practical throughput sweet spot.
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
- peripheral latency
- packets per connection event
- operation type: notification, indication, write without response, or write with response
- direction: one-way or bidirectional
- whether extra BLE connections or advertising stay active during the transfer

Then estimate with the unit that matches the measurement source:

- if you know **ATT messages per event**:
  `throughput_bytes_per_second ≈ att_messages_per_event * app_bytes_per_att_message / connection_interval_seconds`
- if you know **LL packets per event**:
  `throughput_bytes_per_second ≈ ll_packets_per_event * useful_app_bytes_per_ll_packet / connection_interval_seconds`

Never mix ATT-message counts with LL-packet payload sizes in the same formula.

Common useful payload sizes:
- default ATT MTU 23 -> **20** app bytes
- DLE + ATT MTU >= 247 -> **244** app bytes in one full LL packet
- DLE + ATT MTU >= 498 -> **495** app bytes in two full LL packets
- otherwise -> use the actual negotiated limits and account for fragmentation

Useful expectations:
- no DLE -> throughput is often capped around **54 kB/s**
- well-tuned embedded-to-embedded links can reach about **178 kB/s**
- well-tuned links with a mobile device on one side are often around **90-100 kB/s**
- 2M PHY is typically about **77% faster** than 1M for throughput, not 100%
</quick_start>

<reference_index>
Read domain knowledge from `references/`:

- `references/foundations.md` — packet math, overhead, formulas, ceilings, and why PHY rate is not app throughput
- `references/mobile-platforms.md` — iOS, Android, and embedded-central constraints that change tuning advice
- `references/transfer-methods.md` — transfer primitives, 244/495-byte chunk sizing, segmentation, windowing, and bidirectional tradeoffs
- `references/tuning-checklist.md` — ordered tuning sequence and anti-patterns
- `references/measurement-and-troubleshooting.md` — what to capture, how to use sniffers, expected ranges, and symptom-to-cause mapping
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
- accounts for transfer method, chunk sizing, and half-duplex tradeoffs
- recommends a concrete next experiment or configuration change
</success_criteria>
