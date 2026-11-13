# Issue and validate identity certificates

Status: ready-for-agent
Type: AFK
Blocked by: 20

## Behavior

Each node issues its own identity certificate, signed with its Ed25519 identity key, and validates certificates from peers. Code lives once in Kotlin common, over `CryptoProvider`.

- **Layouts:** classical (profile `0x01`, 138 B) and post-quantum (profile `0x02`, 1,354 B with the 1,216 B X-Wing static key). Field offsets follow identity.md.
- **Signature:** Ed25519 over `DS("certificate")` followed by every byte before the signature (D-11).
- **Validation**, in the spec's order:
  1. version, profile and exact length;
  2. `0 < not_after − not_before ≤ 86,400`;
  3. the signature;
  4. `H("node-id", ed25519_key)` equals the NodeId in use;
  5. a seal is allowed only while `now < not_after + 30 s`;
  6. `now ≥ not_before − 30 s`.
- **Clock skew (owner decision, 2026-11-13):** the 30 s skew applies on both edges, for sealing and for use. A certificate is usable from `not_before − 30 s` through `not_after + 30 s`.
- **Threat modeling first:** before any implementation, this ticket analyzes the extended edges in the threat model. If the analysis finds that a profile guarantee is weakened, the agent stops and asks the owner before writing code.
- **Issuing:** the node issues for its current static keys with a window of at most 24 h, using the injected `Clock`.

The `Unsecured` profile issues no certificate. Lookup, caching and key rotation belong to tickets 44 and 52.

## Spec and ADR references

- [identity.md, Identity certificate](../../../docs/spec/identity.md#identity-certificate), [Classical layout](../../../docs/spec/identity.md#classical-layout-profile-0x01), [Post-quantum layout](../../../docs/spec/identity.md#post-quantum-layout-profile-0x02), [Signature](../../../docs/spec/identity.md#signature) and [Validation](../../../docs/spec/identity.md#validation).
- [D-11](../../../docs/spec/decisions.md#d-11-certificate-version-and-signature-input).
- [ADR 0003, Decision](../../../docs/adr/0003-installation-identity-key-model.md#decision) and [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision).
- [ADR 0001, Decision](../../../docs/adr/0001-threat-model-and-profile-guarantees.md#decision) (profile guarantees) and the [threat model](../../../docs/explanation/threat-model.md), which this ticket extends first.
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) (codec rules) and [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 9 and 10.
- [Spec change control](../../../docs/spec/README.md#status-and-change-control): the clarifying sentence in identity.md "Validation" is a spec change.

## Acceptance criteria

- [ ] First, before any code: `docs/explanation/threat-model.md` gains an analysis of the extended edges (sealing up to `not_after + 30 s`, use from `not_before − 30 s`): what an attacker with a just-expired or not-yet-valid certificate gains, against which profile guarantee. If a profile guarantee changes, an ADR 0001 amendment records it. If the analysis finds a weakened guarantee, the agent stops and asks the owner (AGENTS.md ASK) and writes no implementation until answered.
- [ ] Spec change, following [Status and change control](../../../docs/spec/README.md#status-and-change-control): identity.md "Validation" gains one sentence stating that the 30 s skew applies on both edges for sealing and use (usable from `not_before − 30 s` through `not_after + 30 s`); a new D-xx entry in `docs/spec/decisions.md` records it with its evidence and alternatives; any affected data JSON is updated and vectors are regenerated with `python3 tools/spec/gen_vectors.py`; `python3 tools/spec/check_spec.py` passes.
- [ ] The certificate codec passes every `certificate.json` tier 1 test in the one runner, for both layouts.
- [ ] Issuing and validation pass the Rust-generated tier 2 certificate vectors from ticket 20 byte for byte, and the runner no longer lists them as pending.
- [ ] The `certificate-signing-input` group in `derivations.json` passes.
- [ ] The decoder has a deterministic mutation test (ADR 0017 decision 9).
- [ ] T10: each validation rule has a failing case: wrong version, a profile other than the mesh's, short and long input, `not_after ≤ not_before`, a window of 86,401 s, a flipped signature bit, a signature by another key, a NodeId mismatch, sealing 31 s after `not_after`, and use 31 s before `not_before`. Each returns a typed failure and changes no state.
- [ ] Skew edges pass: use exactly 30 s before `not_before` and sealing up to 30 s after `not_after` are accepted; 31 s on either side is rejected.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/spec/gen_vectors.py
python3 tools/spec/check_spec.py
python3 tools/check_adrs.py
```

## Docs to update

- [Threat model](../../../docs/explanation/threat-model.md): the extended-edge analysis, written first.
- [ADR 0001](../../../docs/adr/0001-threat-model-and-profile-guarantees.md): a dated amendment, only if a profile guarantee changes.
- [identity.md, Validation](../../../docs/spec/identity.md#validation): the clarifying skew sentence; [decisions.md](../../../docs/spec/decisions.md): the new D-xx entry; regenerated vectors if a case changes.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the certificate tier 2 set as checked in Kotlin.
- `GLOSSARY.md`: add "Identity certificate" if no entry covers it.

## Devices needed

None. Host and simulator only.
