# Placeholder layouts (not the spec)

Bit offsets are LSB-first within the bit-packed header word of B and C. Every byte-string field in B and C starts on a byte boundary. Integers in A are little-endian. "rsv" bits must be zero.

## Routed unicast (type 1)

| Field | A | B |
|---|---|---|
| version | u8 | bits 0–2 |
| type | u8 | bits 3–7 |
| flags: ackRequested, e2e, priority, extension | u8 (bits 0–3; 4–7 rsv) | bits 8–11 |
| hop limit | u8 | bits 12–19 |
| lifetime s (120..4095) | u16 | bits 20–31 |
| payload length (0..16383) | u16 | bits 32–45 |
| rsv | — | bits 46–47 |
| source tag | 8 B | 8 B |
| destination tag | 8 B | 8 B |
| message ID | 12 B | 12 B |
| if extension: extension length u8 + bytes (skipped) | 1 B + n | 1 B + n |
| payload | payload length bytes | payload length bytes |

Header without extension and payload: A 36 B, B 34 B.

## Mesh broadcast (type 2)

Like unicast, with flags signed, priority, extension (bit 3 rsv), one origin tag instead of source and destination, and, if signed, a 64 B Ed25519 signature after the message ID. Header with signature: A 92 B, B 90 B.

## Fragment (type 3)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| fragment index (0..1023) | u16 | bits 8–17 |
| last fragment | u8 (bit 0; 1–7 rsv) | bit 18 |
| byte offset (0..16383) | u16 | bits 19–32 |
| rsv | — | bits 33–39 |
| message ID | 12 B | 12 B |
| data (rest of frame, ≥ 1 B, offset + size ≤ 16384) | | |

Header: A 19 B, B 17 B.

## Delivery receipt (type 4)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| status (0..4) | u8 | bits 8–10 |
| hops taken | u8 | bits 11–18 |
| rsv | — | bits 19–23 |
| acknowledged message ID | 12 B | 12 B |

A 16 B, B 15 B.

## Plumtree IHAVE (type 5)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| count (1..32) | u8 | bits 8–13 |
| round | u8 | bits 14–21 |
| rsv | — | bits 22–23 |
| message IDs | count × 12 B | count × 12 B |

A 4 + 12n B, B 3 + 12n B.

## Graft (type 6)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| round | u8 | bits 8–15 |
| message ID | 12 B | 12 B |

A 15 B, B 14 B.

## Catch-up digest (type 7)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| more follows | u8 (bit 0; 1–7 rsv) | bit 8 |
| window s (1..63) | u8 | bits 9–14 |
| count (0..512) | u16 | bits 15–24 |
| rsv | — | bits 25–31 |
| message IDs | count × 12 B | count × 12 B |

A 6 + 12n B, B 4 + 12n B.

## Handshake-payload envelope (type 8)

| Field | A | B |
|---|---|---|
| version, type | u8, u8 | bits 0–7 |
| suite (0..15) | u8 | bits 8–11 |
| capabilities (12 bits) | u16 (bits 12–15 rsv) | bits 12–23 |
| has resolving key | u8 (bit 0; 1–7 rsv) | bit 24 |
| key day (days since 2026-01-01) | u16 | bits 25–40 |
| rsv | — | bits 41–47 |
| resolving key, if present | 32 B | 32 B |

With key: A 40 B, B 38 B.

## Advertisement metadata (16 B, fixed, no type)

| Field | A | B |
|---|---|---|
| format version (must be 1) | u8 (2 bits used, rest rsv) | bits 0–1 |
| epoch low bits | u8 (4 bits used) | bits 2–5 |
| capability flags | u8 (5 bits used) | bits 6–10 |
| density class (0..2) | u8 (2 bits used) | bits 11–12 |
| random fill | 0 B | bits 13–31 (19 bits) |
| mesh tag | 4 B | 4 B |
| resolvable tag | 8 B | 8 B |

A has no room left for random fill; B leaves 19 bits.
