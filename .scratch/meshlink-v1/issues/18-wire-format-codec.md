# What is the wire format, and which codec implements it?

Type: prototype
Status: open
Blocked by: 12, 13

## Question

Define the v1 wire format: framing, fragmentation and reassembly, versioning and extension rules, node ID size, and identity-certificate encoding. Then decide how MeshLink encodes and decodes it.

## Decided constraints (2026-11-02)

- **The protocol spec is the authority.** The byte layout is defined in the implementation-independent spec (ADR 0006) with conformance vectors. No library defines the wire format; a codec library is only an implementation choice.
- **Kompact** (`../kompact`, `ch.trancee.kompact:kompact`, a KMP LSB-first bit-packing library, public domain) is the candidate codec.
  - The prototype compares it with a hand-written codec on the same frames and reports: encoded size, decode safety on truncated, over-long and fuzzed input, allocation, Android and iOS throughput, and API ergonomics.
  - If adopted, v1 uses only the runtime artifact, without the KSP code-generation plugin. Adoption needs an E3 register row and user approval.
  - Known gaps to address: pre-1.0 API, no schema versioning (MeshLink defines its own version field and extension rules), no fuzzing evidence found in the repository.
- **Inputs from ADR 0008:** routing tag 8 B, message ID 12 B, 1-byte hop limit, message lifetime, reassembly at every hop. One wire format serves every density class.
- **Inputs from ADR 0009** (once merged): GATT and L2CAP CoC bearers carry the same frames.
- Bit-packing mainly helps the 31 B legacy advertisement (ticket 13); routing frames gain about 1–3 B.
- **Inputs from ADR 0010:** the 16 B rotating 128-bit UUID (mesh tag, resolvable tag, flags, random; placeholder 8/4/1/3 B), the mesh-tag and resolvable-tag derivations and their vectors, the GATT identifier characteristic, and the resolving-key exchange in the handshake payload plus its rotation message over the link. The advertisement itself is fixed at 32-bit `MESH` + 128-bit UUID, so bit-packing applies only inside the 16 B.
