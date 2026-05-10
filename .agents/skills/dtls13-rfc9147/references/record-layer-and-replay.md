# DTLS 1.3 RFC 9147 — Record Layer, Replay Protection, and Sequence Numbers

<overview>
## What RFC 9147 Defines

Datagram Transport Layer Security (DTLS) Protocol Version 1.3. IETF Standards Track (April 2022). Obsoletes RFC 6347 (DTLS 1.2).

TLS 1.3 adapted for datagram (UDP) transport. Preserves datagram semantics (no order guarantee). Same security guarantees as TLS 1.3 **except**: no order protection and replay protection is optional.

### Why DTLS Exists (4 problems TLS can't handle over datagrams)
1. **Implicit sequence numbers** — TLS relies on implicit record numbering; lost records break decryption. DTLS adds explicit sequence numbers.
2. **Lock-step handshake** — TLS requires ordered message delivery. DTLS adds message sequence numbers for reassembly.
3. **Message size** — Handshake messages can exceed MTU. DTLS adds fragmentation/reassembly.
4. **DoS amplification** — UDP is spoofable. DTLS adds return-routability check (cookie exchange via HelloRetryRequest).

### Key Terminology
- **Epoch:** One set of cryptographic keys. Starts at 0, increments on each KeyUpdate. 64-bit internal counter, 2-byte on wire.
- **CID:** Connection ID — allows endpoint address changes without losing association.
- **MSL:** Maximum Segment Lifetime (from TCP spec).
</overview>

<record_layer>
## Record Layer (§4)

### Record Formats

**DTLSPlaintext** (unprotected):
```
ContentType type;
ProtocolVersion legacy_record_version;   // {254, 253}
uint16 epoch = 0;
uint48 sequence_number;
uint16 length;
opaque fragment[length];
```

**DTLSCiphertext** (protected) — variable-length unified header:
```
Bit fields of first byte:  0|0|1|C|S|L|E|E
  C = CID present (0x10)
  S = sequence number size: 0=8-bit, 1=16-bit (0x08)
  L = length present (0x04)
  E E = low 2 bits of epoch (0x03)
```

Fixed bits `001` ensure demultiplexing with DTLS 1.2 and non-DTLS traffic (RFC 7983).

### Minimal Ciphertext Header
Without CID, 8-bit sequence number, no length field = **2 bytes total** (1 byte header + 1 byte seq). Length can be omitted for last record in a datagram.

### Sequence Number and Epoch (§4.2)

- Separate sequence number space per epoch, starting at 0
- Full record number = 128 bits: `{uint64 epoch; uint64 sequence_number}`
- Used as nonce input to AEAD and in ACK messages
- Epoch MUST NOT wrap; establish new association instead
- Sequence number MUST NOT wrap; rekey or abandon association

### Sequence Number Reconstruction (§4.2.2)

Receiver gets only partial epoch (2 bits) and partial sequence number (8 or 16 bits). Reconstruction:
- If epoch bits match current epoch: reconstruct sequence number closest to (highest_received + 1)
- If epoch bits don't match: use most recent past epoch with matching bits, then reconstruct similarly

### Record Number Encryption (§4.2.3)

Sequence numbers are encrypted in DTLSCiphertext records using a mask:
- **AES-based AEAD:** `Mask = AES-ECB(sn_key, Ciphertext[0..15])`
- **ChaCha20-based AEAD:** `Mask = ChaCha20(sn_key, Ciphertext[0..3], Ciphertext[4..15])`
- `sn_key = HKDF-Expand-Label(Secret, "sn", "", key_length)` — new key per epoch
- XOR leading mask bytes with on-wire sequence number
- Ciphertext must be ≥ 16 bytes; pad short plaintexts if needed
- Prevents on-path tracking via sequence number pattern correlation
</record_layer>

<anti_replay>
## Anti-Replay (§4.5.1)

### Sliding Window Mechanism
Borrowed from IPsec ESP (RFC 4303 §3.4.3). **Optional** — packet duplication isn't always malicious (routing errors).

**Separate sliding window per epoch.** Initialize received record counter to zero when epoch first used.

### Algorithm
1. Maintain a **bitmap window** of received sequence numbers
2. The **right edge** = highest validated sequence number received in this epoch
3. Records with sequence numbers **below the left edge** → silently discard (too old)
4. Records **within the window** → check bitmap for duplicates; if already received → silently discard
5. Records **to the right of the window** → new; advance window
6. **Window MUST NOT be updated until record is successfully deprotected** (prevents attacker from advancing window with forged records)

### Timing Channel Consideration
- Replay check SHOULD happen **after deprotection** — rejecting before deprotection creates a timing channel leaking the record number
- Even computing the full record number from partial is a potential (weaker) timing channel

### Window Size
- Receiver chooses window size locally (not communicated to sender)
- SHOULD be large enough to handle plausible reordering for the data rate
- Implemented as a bitmask (see RFC 4303 §3.4.3 for efficient implementation)

### Invalid Record Handling (§4.5.2)
- Invalid records (bad format, length, MAC) SHOULD be **silently discarded** — preserve association
- MAY log for diagnostics
- Generating alerts is NOT RECOMMENDED over UDP (DoS via forged packets)
- Over forgery-resistant transport (SCTP with SCTP-AUTH), alerts are safer
</anti_replay>

<aead_limits>
## AEAD Usage Limits (§4.5.3)

DTLS has **two** limits per AEAD algorithm — both must be enforced:

### 1. Confidentiality Limit (records protected)
Maximum records that can be encrypted with the same keys before key update required:

| AEAD | Max Protected Records |
|------|-----------------------|
| AEAD_AES_128_GCM | 2^24.5 (per TLS 1.3) |
| AEAD_AES_256_GCM | 2^24.5 (per TLS 1.3) |
| AEAD_CHACHA20_POLY1305 | 2^62 (per TLS 1.3) |
| AEAD_AES_128_CCM | **2^23** (Appendix B) |

### 2. Integrity Limit (failed authentication attempts)
Maximum records that fail authentication before connection SHOULD be closed:

| AEAD | Max Failed Auth |
|------|-----------------|
| AEAD_AES_128_GCM | 2^36 |
| AEAD_AES_256_GCM | 2^36 |
| AEAD_CHACHA20_POLY1305 | 2^36 |
| AEAD_AES_128_CCM | **2^23.5** (Appendix B) |

**DTLS-specific concern:** TLS closes connection on any auth failure, so integrity limit is trivially 1. DTLS silently discards invalid records → multiple forgery attempts are possible → explicit counting and limits are required.

### AEAD_AES_128_CCM_8 (Short Tag)
- 64-bit authentication tag → forgery limit is only **2^7** (128 attempts)
- Confidentiality limit same as CCM (2^23)
- TLS_AES_128_CCM_8_SHA256 **MUST NOT be used in DTLS** without additional forgery protections

### Key Update Trigger
- Implementations SHOULD initiate key update **before** reaching limits
- Previous keys retained until new keys confirmed (ACK received and message decrypted with new keys)
- Epoch MUST NOT exceed 2^48-1 (sending side)
</aead_limits>

<handshake_and_key_update>
## Handshake and Key Updates

### Cookie Exchange (DoS Prevention, §5.1)
```
Client                              Server
ClientHello          ------>
                     <------  HelloRetryRequest + cookie
ClientHello + cookie ------>
[rest of handshake]
```
- Cookie MUST depend on client address
- MUST NOT be forgeable by anyone other than issuer
- Servers SHOULD use cookies unless amplification is not a threat
- Clients MUST be prepared for cookie exchange on every handshake

### Retransmission (§5.8)
- Timer-based retransmission for lost handshake messages
- Initial timer: 1 second (SHOULD, ≤1s for low-RTT, MAY be longer)
- Doubles on each retransmission (exponential backoff)
- Max: no specific cap defined
- ACK mechanism (§7) for reliable delivery of handshake messages

### Key Update (§8)
- KeyUpdate message indicates sender is updating keys
- MUST be acknowledged (ACK)
- MUST NOT send with new keys or send new KeyUpdate until previous acknowledged
- Receiver retains pre-update keys until successful decryption with new keys
- Epoch incremented on each KeyUpdate

### Connection IDs (§9)
- NewConnectionId message to provide fresh CIDs to peer
- RequestConnectionId to ask peer for more CIDs
- SHOULD use fresh CID when changing local address/port (privacy)
- Sequence number encryption prevents tracking across paths even with same CID
</handshake_and_key_update>
