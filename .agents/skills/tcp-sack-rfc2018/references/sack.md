# TCP SACK RFC 2018 — Option Format, Sender/Receiver Behavior, and Examples

<overview>
## What RFC 2018 Defines

TCP Selective Acknowledgment Options. IETF Standards Track (October 1996).

Adds selective acknowledgment (SACK) to TCP so the receiver can report non-contiguous blocks of received data. Without SACK, TCP's cumulative ACK only reveals one lost packet per RTT. With SACK, the sender knows exactly which segments are missing and retransmits only those — critical when multiple packets are lost from one window.

### Two TCP Options
1. **SACK-Permitted** (Kind=4) — sent in SYN to enable SACK for the connection
2. **SACK** (Kind=5) — sent on established connections to report received blocks
</overview>

<sack_permitted>
## SACK-Permitted Option (§2)

Sent in SYN only. Signals that the sender can receive and process SACK options.

```
+---------+---------+
| Kind=4  | Length=2|
+---------+---------+
```

- 2 bytes total
- MUST NOT be sent on non-SYN segments
- Both sides must send SACK-Permitted in their SYN for SACK to be used
</sack_permitted>

<sack_option>
## SACK Option Format (§3)

Reports non-contiguous blocks of received data as (left edge, right edge) pairs.

```
+--------+--------+
| Kind=5 | Length |
+--------+--------+--------+--------+
|      Left Edge of 1st Block       |   ← first sequence number of block
+--------+--------+--------+--------+
|      Right Edge of 1st Block      |   ← sequence number AFTER last byte
+--------+--------+--------+--------+
|              ...                   |
+--------+--------+--------+--------+
|      Left Edge of nth Block       |
+--------+--------+--------+--------+
|      Right Edge of nth Block      |
+--------+--------+--------+--------+
```

### Key Details
- Each edge is a **32-bit unsigned integer** in network byte order
- Left Edge = first sequence number of the block (inclusive)
- Right Edge = sequence number immediately **following** the last byte (exclusive)
- Bytes at (Left Edge - 1) and (Right Edge) have NOT been received
- Length = 8×n + 2 bytes for n blocks
- **Maximum 4 blocks** (40 bytes TCP option space: 4 × 8 + 2 = 34 bytes)
- **Maximum 3 blocks** when Timestamp option is also used (10 bytes + 2 padding = 12 bytes consumed)
- Does NOT change the meaning of the cumulative Acknowledgment Number field

### Advisory Nature
SACK is **advisory** — the receiver is permitted to later discard data it has already reported in a SACK option ("reneging"). The sender MUST NOT free data from retransmission queue based solely on SACK; only the cumulative ACK advances the left window edge.
</sack_option>

<receiver_behavior>
## Receiver Behavior (§4)

If SACK-Permitted was received in the SYN:
- SACK options SHOULD be included in **all ACKs that don't ACK the highest sequence number** in the receiver's queue (i.e., whenever the receiver holds non-contiguous data)
- Receiver SHOULD send ACK for every valid segment with new data; each duplicate ACK SHOULD carry SACK

### Block Ordering Rules

**Rule 1 — First block MUST be the triggering segment:**
The first SACK block MUST specify the contiguous block containing the segment that triggered this ACK (unless that segment advanced the cumulative ACK). This ensures the sender always learns the most recent change.

**Rule 2 — Include as many blocks as possible:**
Fill available option space with distinct SACK blocks.

**Rule 3 — Repeat recent blocks for robustness:**
After the first block, fill remaining space by repeating the most recently reported SACK blocks (from first-block positions in previous SACK options) that are not subsets of blocks already in the current option. This ensures each non-contiguous block is reported in **at least 3 successive SACK options** — surviving up to 2 lost ACKs.

### Efficiency (§6)
- On a lossless return path, one block per SACK is sufficient — sender takes union of all first blocks to reconstruct receiver's queue
- Redundant blocks handle ACK loss — each block repeated ~3 times
- Worst case: all ACKs reporting a block are lost → sender unnecessarily retransmits (still no worse than non-SACK TCP)
</receiver_behavior>

<sender_behavior>
## Sender Behavior (§5)

### Scoreboard Implementation
For each segment in the retransmission queue, maintain a **SACKed flag bit**:

1. When SACK option received: turn on SACKed bit for all segments **wholly contained** within each reported block
2. During retransmission: **skip** SACKed segments
3. Any segment with SACKed=off that is below the highest SACKed segment is available for retransmission

### Retransmit Timeout
- After RTO: **turn off ALL SACKed bits** (receiver may have reneged)
- MUST retransmit the segment at the left window edge regardless of SACKed bit
- A segment is only dequeued when the cumulative ACK advances past it

### Congestion Control (§5.1)
- All existing congestion control algorithms MUST be preserved
- Recovery is NOT triggered by a single out-of-order ACK (robustness against reordering)
- During recovery: limit segments sent per ACK (1 for fast recovery, 2 for slow-start)
- Timeout fallback: MUST ignore prior SACK information (receiver may have reneged)
- Reducing congestion window in response to loss: unchanged
</sender_behavior>

<reneging>
## Reneging (§8)

The receiver MAY discard data already reported in SACK options (e.g., buffer pressure). This is "reneging."

**Discouraged** but permitted. When reneging:
- First SACK block MUST still report the newest segment (even if about to be discarded)
- All other SACK blocks MUST NOT report data no longer held

**Sender consequence:** MUST NOT discard data from retransmission queue until acknowledged by the cumulative ACK field. SACKed bits are advisory only.
</reneging>

<examples>
## Examples (§7)

Left window edge = 5000, 8 segments of 500 bytes each (5000-8999).

### Case 2: First segment lost, remaining 7 received
Each arriving segment triggers a duplicate ACK with growing SACK block:

| Trigger | Cumulative ACK | SACK Block |
|---------|---------------|------------|
| 5500 | 5000 | 5500-6000 |
| 6000 | 5000 | 5500-6500 |
| 6500 | 5000 | 5500-7000 |
| ... | 5000 | 5500-(grows) |
| 8500 | 5000 | 5500-9000 |

### Case 3: Alternating segments lost (2nd, 4th, 6th, 8th)
Multiple SACK blocks grow, most recent first:

| Trigger | ACK | 1st Block | 2nd Block | 3rd Block |
|---------|-----|-----------|-----------|-----------|
| 6000 | 5500 | 6000-6500 | | |
| 7000 | 5500 | 7000-7500 | 6000-6500 | |
| 8000 | 5500 | 8000-8500 | 7000-7500 | 6000-6500 |

When 4th packet (6500) arrives out of order, blocks merge:

| Trigger | ACK | 1st Block | 2nd Block |
|---------|-----|-----------|-----------|
| 6500 | 5500 | 6000-7500 | 8000-8500 |

When 2nd packet (5500) arrives, cumulative ACK advances:

| Trigger | ACK | 1st Block |
|---------|-----|-----------|
| 5500 | 7500 | 8000-8500 |
</examples>
