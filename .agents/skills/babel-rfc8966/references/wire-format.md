# Babel RFC 8966 — Wire Format & TLVs

<packet_format>
## Packet Format (§4.2)

Babel packets are sent as UDP datagrams with hop count 1. Port: **6696**. IPv6 multicast: **ff02::1:6**. IPv4 multicast: **224.0.0.111**.

Source MUST be link-local (IPv6) or local network (IPv4). Packets from other sources silently ignored.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Magic = 42   | Version = 2   |        Body length            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          Packet Body (TLVs)...
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
|          Packet Trailer (TLVs)...
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
```

- Magic=42, Version=2. Ignore packets with different values.
- Body length excludes header and trailer.
- Trailer: only TLVs explicitly allowed there (Pad1, PadN by default). Used for crypto signatures.
- Max size: interface MTU minus lower-layer headers, or 512 octets, whichever is larger (not exceeding 2^16-1).
- Jitter: TLVs SHOULD be buffered and sent with random delay (avoids sync, enables aggregation).
</packet_format>

<address_encodings>
## Address Encodings (§4.1.4)

| AE | Name | Size | Compression |
|----|------|------|-------------|
| 0 | Wildcard | 0 octets | N/A |
| 1 | IPv4 | ≤4 octets | Allowed |
| 2 | IPv6 | ≤16 octets | Allowed |
| 3 | Link-local IPv6 | 8 octets (fe80::/64 implied) | Not allowed |
</address_encodings>

<tlv_types>
## TLV Types (§4.6)

All TLVs (except Pad1) have: Type (1 byte), Length (1 byte), Payload.
Unknown TLV types MUST be silently ignored.

| Type | Name | Purpose |
|------|------|---------|
| 0 | **Pad1** | 1-byte padding (no Length field) |
| 1 | **PadN** | Multi-byte padding |
| 2 | **Acknowledgment Request** | Request ack within Interval centiseconds. Opaque echoed back. |
| 3 | **Acknowledgment** | Reply to ack request. MUST be unicast. |
| 4 | **Hello** | Neighbour discovery. Carries: Flags (U=unicast), Seqno, Interval (centiseconds). |
| 5 | **IHU** | "I Heard You". Carries: AE, Rxcost, Interval, Address. |
| 6 | **Router-Id** | Sets implied router-id for subsequent Updates. 8 octets. MUST NOT be all-zeros or all-ones. |
| 7 | **Next Hop** | Sets implied next-hop for subsequent Updates in matching address family. |
| 8 | **Update** | Advertise/retract route. Carries: AE, Flags (P=prefix, R=router-id), Plen, Omitted, Interval, Seqno, Metric, Prefix. |
| 9 | **Route Request** | Request update for prefix. AE=0 → wildcard (full dump). |
| 10 | **Seqno Request** | Request update with specific seqno. Carries: AE, Plen, Seqno, Hop Count, Router-Id, Prefix. |

### Hello Flags
- **U (bit 0, 0x8000):** Unicast Hello (per-neighbour seqno). If unset, Multicast Hello (per-interface seqno).

### Update Flags
- **P (bit 0, 0x80):** Set default prefix for subsequent Updates with same AE
- **R (bit 1, 0x40):** Derive router-id from last 8 octets of prefix (or zero-padded for shorter addresses)

### Update Encoding
- Metric = 0xFFFF → retraction. Router-id, next-hop, seqno not used.
- AE=0 with infinite metric → retract all routes from this sender
- AE=0 with finite metric → MUST be ignored
- Prefix compression: first `Omitted` octets from preceding Update with same AE and P flag
</tlv_types>

<sub_tlvs>
## Sub-TLVs (§4.4)

Same structure as TLVs. Stored in the gap between a TLV's natural size and its Length.

**Mandatory bit** (bit 7 of Type, types 128-255): if set and sub-TLV is unknown → ignore the **entire enclosing TLV** (except parser state updates from Router-Id, Next Hop, Update). If not set → silently ignore just the sub-TLV.

| Type | Name |
|------|------|
| 0 | Pad1 |
| 1 | PadN |
| 2 | Diversity (draft) |
| 3 | Timestamp (draft) |
| 128 | Source Prefix (draft) |
</sub_tlvs>

<parser_state>
## Stateful Parser (§4.5)

Parser maintains state across TLVs within a packet:
1. **Default prefix** per AE (updated by Update with P flag)
2. **Current next-hop** per address family (initialized from packet source; updated by Next Hop TLV)
3. **Current router-id** (undefined at start; updated by Router-Id TLV or Update with R flag)

**Critical rule:** Parser state is updated **before** checking mandatory sub-TLVs. A TLV with unknown mandatory sub-TLV still updates parser state.

Trailer uses a separate stateless parser (no state-modifying TLVs allowed).
</parser_state>
