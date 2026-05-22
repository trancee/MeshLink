# The Trust Model: TOFU Pinning in MeshLink

## The problem

MeshLink is intentionally offline-first. There is no account system, no
certificate authority, and no backend that can tell a device which peer keys
are "correct". The SDK still needs a practical way to decide whether a peer
should be trusted for encrypted messaging.

## Trust on first use (TOFU)

MeshLink uses **trust on first use**.

On the first successful authenticated contact with a peer:

1. The hop-to-hop Noise XX handshake completes.
2. MeshLink verifies the remote static identity material is internally
   consistent.
3. The peer's identity is pinned locally as a `TrustRecord`.
4. Future contacts with that peer must present the same Ed25519 and X25519
   public keys.

This is the same operational model many developers already know from SSH: the
first encounter establishes continuity, and later encounters are checked against
that original pin.

## What is pinned

Each `TrustRecord` persists the minimum identity material needed for continuity:

- `peerId`
- `ed25519PublicKey`
- `x25519PublicKey`
- `firstSeenAt`
- `lastVerifiedAt`
- `status`

The pinned keys are stored in platform secure storage through MeshLink's
`SecureStorage` abstraction.

## What happens when a peer identity changes

If a previously trusted peer presents different keys later, MeshLink fails
closed.

Current behavior in this repository state:

- the original trust record is **not** overwritten automatically
- the attempted contact is treated as untrusted
- `DiagnosticCode.TRUST_FAILURE` is emitted with redacted metadata
- delivery returns an explicit trust-failure outcome instead of silently
  downgrading or accepting the new identity

This keeps identity continuity deterministic and prevents accidental trust
replacement.

## Why this fits an offline mesh

TOFU is a pragmatic fit for MeshLink's constraints:

- **No servers required** — trust decisions remain fully local
- **No provisioning ceremony required** — the first message flow stays simple
- **Works across Android and iOS** — the trust semantics live in shared
  `commonMain` logic
- **Fails closed on mismatch** — unexpected identity changes surface explicitly

The trade-off is the classic TOFU limitation: if the very first encounter is
already being intercepted, continuity will be pinned to the attacker's
identity. MeshLink does not claim to solve that first-contact problem
automatically.

## Trust and delivery

Once a peer is trusted:

- hop-to-hop sessions can be established for adjacent links
- end-to-end payload sealing can use the pinned static identity for the final
  destination
- relay nodes can forward traffic without learning end-to-end plaintext

If trust cannot be established or verified, MeshLink does not silently retry in
plaintext or expose partially trusted delivery.

## Restart behavior

Pinned trust survives SDK and app restarts because it is stored locally. Pending
retry state does **not** survive restart, but previously trusted peers can be
contacted again without re-enrollment as long as their identity material is
unchanged.

## Diagnostics you should expect

Applications integrating MeshLink should watch `diagnosticEvents` for:

- `TRUST_ESTABLISHED` when a new peer is pinned successfully
- `TRUST_FAILURE` when a previously pinned peer presents a different identity

These diagnostics are redacted by design: they provide peer suffixes and stable
reason codes, not full peer identifiers or plaintext payload content.

## What MeshLink trust does and does not provide

### MeshLink trust provides

- local continuity checking for peer identities
- deterministic fail-closed behavior on identity mismatch
- a serverless way to support encrypted offline messaging

### MeshLink trust does not provide

- proof of a peer's real-world identity
- automatic first-contact MITM resistance
- certificate management or remote revocation infrastructure
- silent trust migration when keys change
