# Write the v1 protocol spec

Type: task
Status: resolved

## Question

Write the implementation-independent protocol spec (ADR 0006 format): every frame, record, TLV, certificate and advertisement byte layout following ADR 0012, the shared cross-platform limits table, and conformance vectors (C10).

## Context

- Absorbs the fog items 'exact byte layouts' and 'shared cross-platform limits table'.

## Answer

Protocol 1 is written as a Draft spec in [`docs/spec/`](../../../docs/spec/README.md). It stays Draft, and may change freely, until library 0.1.0; after that C6 and C7 apply.

- **Files:** `README.md` (notation, conventions, registries, change log), `identity.md`, `discovery.md`, `link.md`, `routing.md`, `end-to-end.md` and the [decision log](../../../docs/spec/decisions.md).
- **Authority:** JSON data files in `docs/spec/data/`, each with a schema: limits, constants, frame types, TLV types, domain strings and codes. The Markdown tables repeat them, and `tools/spec/check_spec.py` fails if the two disagree.
- **Normative scope:** bytes, derivations, crypto constructions, state machines, peer-visible timers and limits. Purely local choices are informative and link to their ADRs.
- **Owner-approved rules:**
  - Frame type ranges: link-local `0x01`–`0x3F`, unicast `0x40`–`0x7F`, flood `0x80`–`0xBF`.
  - TLV top bit marks it critical.
  - BLAKE2b for identifiers and tags, HKDF-SHA-256 for keys, each with a length-prefixed `"MeshLink/1 <purpose>"` domain string.
- **Decisions:** 43 byte-level and numeric choices (D-01 to D-43), each accepted by the owner on 2026-11-08 in round 2.
- **Conflicts resolved:** 10 conflicts between ADRs (C-01 to C-10) were resolved by amending ADRs 0002, 0009, 0010, 0012, 0014, 0015 and 0018, and `v1-parameters.md` (rotation is 5 min everywhere). `NEIGHBOR_LIST` now carries the sender's next identifier.
- **Vectors, tier 1:** 253 Wycheproof-style vectors cover every layout and every hash-only derivation. `tools/spec/gen_vectors.py` produces them deterministically, using only Python's standard library.
- **Vectors, tier 2:** these need X25519, Ed25519, AEAD, ML-KEM, X-Wing, HPKE or Noise, so they are listed as pending in `docs/spec/vectors/README.md`. They come from the Kotlin and Rust reference implementations agreeing (ADR 0002, ADR 0004) and are required before 1.0.0 (ADR 0019).
