---
name: tcp-sack-rfc2018
description: Complete reference for RFC 2018 — TCP Selective Acknowledgment Options. Covers the SACK mechanism that allows TCP receivers to report non-contiguous blocks of received data so senders retransmit only missing segments instead of waiting one RTT per loss. Two TCP options defined. SACK-Permitted (Kind=4, 2 bytes, SYN-only) enables SACK for the connection. SACK option (Kind=5, variable length) carries pairs of (left edge inclusive, right edge exclusive) 32-bit sequence numbers defining received blocks — max 4 blocks in 40 bytes of option space, max 3 with Timestamp option. Receiver behavior (first block MUST be the triggering segment for freshness, include as many blocks as possible, repeat recent blocks for robustness so each block appears in ≥3 successive ACKs surviving ACK loss). Sender scoreboard implementation (SACKed flag per segment, skip SACKed during retransmission, after RTO clear ALL SACKed bits because receiver may have reneged, MUST retransmit left-edge segment on timeout, dequeue only on cumulative ACK advance). Reneging (receiver MAY discard SACKed data under buffer pressure — discouraged but permitted, sender MUST NOT free data based on SACK alone). Congestion control preserved (no recovery on single out-of-order ACK, limit segments per ACK during recovery). Includes worked examples showing block merging as gaps fill. Use when implementing selective acknowledgment, designing reliable transfer over lossy links, understanding SACK block generation/interpretation, or adapting SACK concepts to non-TCP protocols.
---

<essential_principles>

**RFC 2018** defines TCP Selective Acknowledgment Options. IETF Standards Track (October 1996).

### What an Implementer Must Know

**Problem:** TCP cumulative ACK reveals only one loss per RTT. Multiple losses from one window are catastrophic.

**SACK-Permitted** (Kind=4, 2 bytes): sent in SYN to enable. **SACK** (Kind=5): (left, right) block pairs as 32-bit sequence numbers. Left = first byte (inclusive), Right = byte after last (exclusive). Max 4 blocks (3 with Timestamps).

**Receiver rules:** First block MUST be the triggering segment. Include max blocks. Repeat recent blocks so each appears in ≥3 ACKs (survives ACK loss). Send SACK in all duplicate ACKs.

**Sender scoreboard:** SACKed flag per retransmission queue segment. Skip SACKed during retransmit. After RTO: clear ALL SACKed bits (receiver may renege). MUST retransmit left-edge on timeout. Dequeue only on cumulative ACK.

**Reneging:** Receiver MAY discard SACKed data (buffer pressure). Discouraged but permitted. Sender MUST NOT free data based on SACK — only cumulative ACK.

**Advisory:** SACK does not change meaning of cumulative ACK field. Congestion control must be preserved.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Everything (problem statement, SACK-Permitted option Kind=4 SYN-only, SACK option Kind=5 block format with left/right edges and max blocks, receiver behavior with 3 ordering rules for freshness and robustness, sender scoreboard with SACKed flags and RTO clearing, reneging rules and consequences, congestion control preservation, efficiency and worst-case ACK loss analysis, worked examples with block merging as gaps fill) | `references/sack.md` |

</routing>

<reference_index>

**sack.md** — overview (TCP SACK, Standards Track October 1996, fixes multiple-loss-per-window problem where cumulative ACK reveals one loss per RTT, two options: SACK-Permitted enables and SACK reports blocks), SACK-Permitted §2 (Kind=4 Length=2 SYN-only 2 bytes both sides must send), SACK option format §3 (Kind=5 variable length, each block: Left Edge first seq number inclusive + Right Edge seq number after last byte exclusive, 32-bit unsigned network byte order, bytes at LeftEdge-1 and RightEdge not received, length=8n+2 max 4 blocks in 40 bytes option space max 3 with Timestamp consuming 12 bytes, does NOT change cumulative ACK meaning, advisory — receiver may later discard SACKed data), receiver behavior §4 (SACK in all ACKs not ACKing highest seq, ACK every valid new-data segment each duplicate ACK bears SACK; rule 1: first block MUST be triggering segment's contiguous block for freshness; rule 2: include max distinct blocks; rule 3: repeat most recently reported blocks not already subsets ensuring ≥3 successive appearances surviving ACK loss), sender behavior §5 (scoreboard: SACKed flag per retransmission queue segment, turn on for segments wholly contained in SACK blocks, skip SACKed during retransmit, unsacked below highest SACKed available for retransmit; RTO: turn off ALL SACKed bits receiver may renege, MUST retransmit left-edge regardless, dequeue only on cumulative ACK), congestion control §5.1 (all existing algorithms preserved, no recovery on single out-of-order ACK, limit segments per ACK during recovery: 1 fast-recovery 2 slow-start, timeout fallback must ignore SACK, congestion window reduction unchanged), efficiency §6 (lossless return path: 1 block per SACK sufficient sender takes union of first blocks, redundant blocks handle ACK loss each repeated ~3 times, worst case: all ACKs for a block lost→unnecessary retransmit still ≤ non-SACK TCP), reneging §8 (receiver MAY discard SACKed data if buffer pressure — discouraged, first block still MUST report newest segment, other blocks MUST NOT report data no longer held, sender MUST NOT free data until cumulative ACK), examples §7 (left edge 5000, 8×500-byte segments: case 2 first lost remaining 7 received shows single growing block, case 3 alternating losses shows multiple blocks with most-recent-first ordering and block merging when gaps fill and cumulative ACK advance when contiguous)

</reference_index>
