# Babel RFC 8966 — Cost Computation, Parameters & Security

<cost_computation>
## Cost Computation (Appendix A)

### Hello History (§A.1)
Per-neighbour, per-Hello-kind: 16-bit vector (1=received, 0=missed) + expected seqno.

On receiving Hello with seqno nr, expected ne:
- Differ by >16 → flush neighbour entry, create new
- nr < ne → "undo history": remove last (ne - nr) entries
- nr > ne → "fast-forward": add (nr - ne) zeros

Then append 1, set ne = nr + 1. Reset hello timer to 1.5× advertised interval.

On timer expiry: add 0, increment expected seqno. If both histories empty → flush neighbour.

### k-out-of-j (§A.2.1) — Wired Links
Parameters: k, j (0 < k ≤ j), nominal cost C ≥ 1. Check last j hellos; if ≥ k received → rxcost = C, else rxcost = infinity.

Applied to both Multicast and Unicast histories; link is up if **either** indicates up.

Cost:
- rxcost = infinity → cost = infinity
- Otherwise → cost = txcost

**Recommended default:** 2-out-of-3, C=96.

### ETX (§A.2.2) — Wireless Links
Beta = estimated probability of receiving a Hello (from Multicast Hello history).
- rxcost = 256 / beta
- alpha = MIN(1, 256/txcost) — probability of successfully sending
- **cost = 256 / (alpha × beta)** = (MAX(txcost, 256) × rxcost) / 256

ETX ignores Unicast Hello history (802.11 ARQ on unicast makes it uninformative).

### Hysteresis (§A.3)
Maintain smoothed metric ms(R) = exponentially smoothed average of m(R), time constant ~3× Hello interval.

- No route selected → select smallest metric (ignore smoothed)
- Route R selected → switch to R' only when **both** m(R') < m(R) AND ms(R') < ms(R)
</cost_computation>

<parameters>
## Recommended Default Parameters (Appendix B)

| Parameter | Default Value |
|-----------|--------------|
| Multicast Hello interval | **4 seconds** |
| Unicast Hello interval | Infinite (disabled) |
| Link cost (wired) | 2-out-of-3, C=96 |
| Link cost (wireless) | ETX |
| IHU interval | 3× Multicast Hello interval (**12 seconds**) |
| IHU actual sending | Every Hello on lossy links; every 3rd Hello on lossless |
| Update interval | 4× Multicast Hello interval (**16 seconds**) |
| IHU Hold time | 3.5× advertised IHU interval |
| Route Expiry time | 3.5× advertised update interval |
| Request timeout | Initially **2 seconds**, doubled each resend, max 3 resends |
| Urgent timeout | **0.2 seconds** |
| Source GC time | **3 minutes** |

### Interval Format
16-bit values in **centiseconds** (hundredths of a second). Max ~11 minutes, granularity 10ms.

### Router-Id
Arbitrary 8 octets, assumed unique. MUST NOT be all-zeros or all-ones. Can be random or derived from link-layer address.
</parameters>

<security>
## Security (§6)

**Babel as defined in the RFC is completely insecure.** Without additional mechanisms, any on-link attacker can:
- Redirect traffic (announce better metric/seqno/longer prefix)
- Crash implementations with malformed packets
- Replay captured packets

### Mitigations
- IPv6 link-local source requirement limits attacks to on-link
- **Two recommended security extensions:**

| Extension | RFC | Approach | Trade-off |
|-----------|-----|----------|-----------|
| **Babel-MAC** | RFC 8967 | HMAC on every packet + freshness proof | Simpler, lighter. Symmetric keys only. No confidentiality. **Recommended when acceptable.** |
| **Babel-DTLS** | RFC 8968 | Babel over DTLS | Asymmetric keys, confidentiality, integrity. More complex. |

**Every implementation SHOULD implement Babel-MAC.**

### Privacy
Mobile node's route announcements reveal physical location. Mitigate with random router-ids + random IPs, changed frequently.
</security>

<extensions>
## Extension Mechanisms (Appendix D)

| Mechanism | When to Use |
|-----------|-------------|
| New TLV type | Incompatible extension; whole TLV ignored by old nodes |
| Non-mandatory sub-TLV | Compatible extension; sub-TLV ignored, rest of TLV processed normally |
| Mandatory sub-TLV | Incompatible sub-extension; whole enclosing TLV ignored |
| New AE | New address encoding; more involved (new compression state). Must not break Next Hop or Router-Id flag |
| Packet trailer | Crypto signatures only; parsed statelessly |
| Version bump | Only for non-backwards-compatible overhaul |

**Recommendation:** New TLVs should be self-terminating and allow sub-TLVs.
</extensions>

<route_filtering>
## Route Filtering (Appendix C)

Filtering is safe with distance-vector: assign infinite metric to filtered routes. Implementations MAY apply arbitrary filters (by interface, prefix, etc.).

**NOT safe:**
- Learning a route with metric **smaller** than advertised (causes loops)
- Replacing a prefix with a **more specific** one (causes loops)

**Minimum default filters (SHOULD):** discard routes for fe80::/64, ff00::/8, 127.0.0.1/32, 0.0.0.0/32, 224.0.0.0/8.
</route_filtering>

<stub_implementations>
## Stub Implementations (Appendix E)

A stub node announces directly-attached prefixes only, never re-announces learned routes. Since it can't forward transit traffic → can't participate in loops → no need for feasibility condition or source table.

**Must still:** parse sub-TLVs + check mandatory bit, answer ack requests, participate in Hello/IHU, reply to seqno requests for own routes, reply to route requests.

Reference sizes: IPv6-only stub ~1000 lines of C, ~13 KB binary. Full implementation ~12,000 lines of C, ~120 KB binary.
</stub_implementations>
