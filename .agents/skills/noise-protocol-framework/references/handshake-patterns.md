# Noise Protocol Framework — Handshake Patterns

<pattern_basics>
## Handshake Pattern Basics (§7.1)

A **message pattern** = sequence of tokens from `{e, s, ee, es, se, ss, psk}`.

A **pre-message pattern** = one of: `"e"`, `"s"`, `"e, s"`, or empty.

A **handshake pattern** consists of:
1. Initiator pre-message pattern (info known to responder)
2. Responder pre-message pattern (info known to initiator)
3. Sequence of message patterns (actual handshake messages)

Pre-messages represent keys exchanged prior to handshake → inputs to Initialize().
First message: initiator → responder. Then alternating.

Pre-messages shown before `"..."` delimiter. Initiator's listed/hashed first.

### Example

```
NK:
  <- s           ← responder pre-message (initiator knows responder's static key)
  ...
  -> e, es       ← initiator sends ephemeral, does DH(e, rs)
  <- e, ee       ← responder sends ephemeral, does DH(e, re)
```
</pattern_basics>

<alice_bob>
## Alice and Bob vs Initiator and Responder (§7.2)

In compound protocols, roles can reverse between Noise handshakes. **Alice** = left party (→ arrows), **Bob** = right party.

**Canonical (Alice-initiated) form:** Assumes initiator = Alice. All processing rules use canonical form.

**Bob-initiated form:** Reverse arrows and swap `es` ↔ `se`. Doesn't change the pattern — just a notational convenience for viewing compound protocols.

Bob-initiated NN:
```
<- e
-> e, ee
```
</alice_bob>

<validity_rules>
## Handshake Pattern Validity (§7.3)

1. **Parties can only DH between keys they possess.**
2. **No duplicate sends:** At most one `"e"` and one `"s"` per party (including pre-messages).
3. **No duplicate DH:** At most one occurrence of `"ee"`, `"es"`, `"se"`, `"ss"` per handshake.
4. **Ephemeral-before-encrypt rule (critical):** After any DH involving the local static key and a remote key, the local party must not encrypt unless it has ALSO done a DH with its local ephemeral and that same remote key:
   - After `"se"` → initiator needs prior `"ee"` before encrypting
   - After `"ss"` → initiator needs prior `"es"` before encrypting
   - After `"es"` → responder needs prior `"ee"` before encrypting
   - After `"ss"` → responder needs prior `"se"` before encrypting

**Why rule 4 matters:**
- Prevents catastrophic key reuse (victim might encrypt with key lacking ephemeral contribution)
- Guarantees forward secrecy and KCI resistance from ephemeral keys
</validity_rules>

<one_way_patterns>
## One-Way Handshake Patterns (§7.4)

One-way stream from sender to recipient. After handshake, sender encrypts with first CipherState from Split(). Second CipherState discarded — recipient MUST NOT send.

Single-character naming = sender's static key status:
- **N** = No static key for sender
- **K** = Static key Known to recipient
- **X** = Static key Xmitted (transmitted) to recipient

```
N:                          K:                          X:
  <- s                        -> s                        <- s
  ...                         <- s                        ...
  -> e, es                    ...                         -> e, es, s, ss
                              -> e, es, ss
```

`N` = conventional DH public-key encryption. `K`/`X` add sender authentication (known vs transmitted).
</one_way_patterns>

<fundamental_interactive>
## Fundamental Interactive Patterns (§7.5)

12 patterns. Two-character naming:

**First character** (initiator's static key):
- **N** = No static key
- **K** = Known to responder
- **X** = Xmitted to responder
- **I** = Immediately transmitted (reduced identity hiding)

**Second character** (responder's static key):
- **N** = No static key
- **K** = Known to initiator
- **X** = Xmitted to initiator

```
NN:                             KN:
  -> e                            -> s
  <- e, ee                        ...
                                  -> e
                                  <- e, ee, se

NK:                             KK:
  <- s                            -> s
  ...                             <- s
  -> e, es                        ...
  <- e, ee                        -> e, es, ss
                                  <- e, ee, se

NX:                             KX:
  -> e                            -> s
  <- e, ee, s, es                 ...
                                  -> e
                                  <- e, ee, se, s, es

XN:                             IN:
  -> e                            -> e, s
  <- e, ee                        <- e, ee, se
  -> s, se

XK:                             IK:
  <- s                            <- s
  ...                             ...
  -> e, es                        -> e, es, s, ss
  <- e, ee                        <- e, ee, se
  -> s, se

XX:                             IX:
  -> e                            -> e, s
  <- e, ee, s, es                 <- e, ee, se, s, es
  -> s, se
```

**XX** is the most generically useful — mutual authentication with static key transmission.

### Key Properties
- **K-ending patterns** (NK, KK, XK, IK) enable **zero-RTT** encryption (initiator encrypts first payload).
- **All patterns** allow **half-RTT** encryption of first response.
- **K/I-starting patterns**: responder only has "weak" forward secrecy until receiving a transport message from initiator, then "strong".
</fundamental_interactive>

<deferred_patterns>
## Deferred Handshake Patterns (§7.6)

Numeral `"1"` after first and/or second character defers that party's authentication DH to the next message.

**23 total deferred variants.** Motivations:
- Avoid 0-RTT data when not wanted
- Improve identity hiding
- Enable future signature or KEM replacement of DH operations

Example — XK and its deferred variants:
```
XK:                  X1K:                 XK1:                 X1K1:
  <- s                 <- s                 <- s                 <- s
  ...                  ...                  ...                  ...
  -> e, es             -> e, es             -> e                 -> e
  <- e, ee             <- e, ee             <- e, ee, es         <- e, ee, es
  -> s, se             -> s                 -> s, se             -> s
                       <- se                                     <- se
```

Full deferred pattern listing in Appendix (§18.1).
</deferred_patterns>

<payload_security>
## Payload Security Properties (§7.7)

Each payload has **source** (sender authentication) and **destination** (confidentiality) properties.

### Source Properties (Authentication)
| Level | Description |
|-------|------------|
| **0** | No authentication. Any party including active attacker could have sent this. |
| **1** | Sender auth **vulnerable to KCI**. Based on `ss` (static-static DH). Compromised recipient key → forgeable. |
| **2** | Sender auth **resistant to KCI**. Based on `es` or `se` (ephemeral-static DH). Cannot be forged if private keys secure. |

### Destination Properties (Confidentiality)
| Level | Description |
|-------|------------|
| **0** | Cleartext. No confidentiality. |
| **1** | Encrypted to ephemeral recipient. Forward secrecy via `ee`. But recipient unauthenticated. |
| **2** | Encrypted to known recipient, **no forward secrecy**, **replayable**. Only static-key DHs. |
| **3** | Encrypted to known recipient, **weak forward secrecy**. ee + es but recipient's ephemeral unverified by sender → active attacker could forge ephemeral then later compromise static to decrypt. |
| **4** | **Weak FS if sender compromised**. ee + es but binding verified only via sender's static → KCI variant enables weak FS attack. |
| **5** | **Strong forward secrecy**. ee + es with verified binding. Cannot decrypt assuming ephemeral keys secure and no active impersonation with stolen static. |

### Security Table — Fundamental Patterns

```
Pattern             Source  Dest    Source  Dest    Source  Dest    Source  Dest
                    msg1→           msg2←           msg3→           transport

N                   0       2
K                   1       2
X                   1       2

NN  -> e            0       0
    <- e, ee        0       1

NK  -> e, es        0       2
    <- e, ee        2       1
    ->              0       5

NX  -> e            0       0
    <- e,ee,s,es    2       1
    ->              0       5

XN  -> e            0       0
    <- e, ee        0       1
    -> s, se        2       1
    <-              0       5

XK  -> e, es        0       2
    <- e, ee        2       1
    -> s, se        2       5
    <-              2       5

XX  -> e            0       0
    <- e,ee,s,es    2       1
    -> s, se        2       5
    <-              2       5

KN  -> e            0       0
    <- e,ee,se      0       3
    ->              2       1
    <-              0       5

KK  -> e,es,ss      1       2
    <- e,ee,se      2       4
    ->              2       5
    <-              2       5

KX  -> e            0       0
    <- e,ee,se,s,es 2       3
    ->              2       5
    <-              2       5

IN  -> e, s         0       0
    <- e,ee,se      0       3
    ->              2       1
    <-              0       5

IK  -> e,es,s,ss    1       2
    <- e,ee,se      2       4
    ->              2       5
    <-              2       5

IX  -> e, s         0       0
    <- e,ee,se,s,es 2       3
    ->              2       5
    <-              2       5
```

Transport payloads listed only when different from preceding handshake payload from same party. Second transport payload properties apply only if first was received.
</payload_security>

<identity_hiding>
## Identity Hiding Properties (§7.8)

Assumes ephemeral private keys secure and parties abort on untrusted static keys.

### Identity Hiding Levels

| Level | Property |
|-------|----------|
| **0** | Transmitted in clear. |
| **1** | Encrypted with forward secrecy, but **sent to anonymous responder**. |
| **2** | Encrypted with forward secrecy, but **can be probed by anonymous initiator**. |
| **3** | Not transmitted, but passive attacker can check candidates for responder's private key. Also can replay to check "same responder" (same static key). |
| **4** | Encrypted to responder's static key, **no forward secrecy**. Responder key compromise → initiator pubkey decrypted. |
| **5** | Not transmitted, but passive attacker can check candidate pairs (responder privkey, initiator pubkey). |
| **6** | Not transmitted, but active attacker posing as initiator without initiator's static key, who later learns a candidate initiator privkey, can check whether the candidate is correct. |
| **7** | Encrypted but **weak forward secrecy**. Active attacker pretending to be initiator, later learning initiator privkey, can decrypt responder's pubkey. |
| **8** | Not transmitted, but active attacker posing as initiator and recording a run can check candidates for responder's pubkey. |
| **9** | Encrypted with forward secrecy to an authenticated party. |

### Identity Hiding Table

```
Pattern     Initiator   Responder
N           -           3
K           5           5
X           4           3
NN          -           -
NK          -           3
NK1         -           9
NX          -           1
XN          2           -
XK          8           3
XK1         8           9
XX          8           1
KN          7           -
KK          5           5
KX          7           6
IN          0           -
IK          4           3
IK1         0           9
IX          0           6
```

Note: NK1, XK1, IK1 (deferred) have **improved** responder identity hiding (level 9) compared to their fundamental counterparts.
</identity_hiding>

<protocol_names>
## Protocol Names (§8)

Format: `Noise_<pattern>_<DH>_<cipher>_<hash>`

Examples:
- `Noise_XX_25519_AESGCM_SHA256`
- `Noise_N_25519_ChaChaPoly_BLAKE2s`
- `Noise_IK_448_ChaChaPoly_BLAKE2b`

Max 255 bytes. Alphanumeric + `"+"` + `"/"` only.

### Pattern Name Section
Base pattern (uppercase + numerals, e.g. `"XX1"`, `"IK"`) + modifiers (lowercase alpha-start, appended directly for first, `+` separated for subsequent). Example: `XXfallback+psk0`.

### Algorithm Name Sections
Each section: one or more algorithm names separated by `+`. Alphanumeric + `"/"`. Usually single algorithm per section. Multiple only for hybrid constructions (e.g., post-quantum forward secrecy).

Modifiers must be sorted alphabetically when order doesn't matter (interoperability convention).
</protocol_names>
