# The Trust Model: TOFU Pinning in MeshLink

## The problem

MeshLink is intentionally offline-first. There is no account system, no
certificate authority, and no backend that can tell a device which peer keys
are "correct". The SDK still needs a practical way to decide whether a peer
should be trusted for encrypted messaging.

## Trust on first use (TOFU)

MeshLink uses **trust on first use**.

On the first successful authenticated contact with a peer:

1. A Noise XX handshake completes — either the hop-to-hop handshake with an
   adjacent peer, or a relayed end-to-end handshake with a peer that is
   several hops away (see
   [Trust can only come from an authenticated handshake](#trust-can-only-come-from-an-authenticated-handshake)).
2. MeshLink verifies the remote static identity material is internally
   consistent.
3. The peer's identity is pinned locally as a `TrustRecord`.
4. Future contacts with that peer must present the same Ed25519 and X25519
   public keys.

This is the same operational model many developers already know from SSH: the
first encounter establishes continuity, and later encounters are checked against
that original pin.

## Trust can only come from an authenticated handshake

Trust is never learned from data a peer merely *asserts* — only from the
outcome of a Noise XX handshake that MeshLink itself drove and verified.
Concretely, MeshLink never pins or refreshes trust from:

- routing metadata gossiped by other peers (a route announcement says a peer
  *exists*, not that its claimed keys are genuine)
- sender identity fields carried inside a message envelope (a relay or a
  malicious peer could otherwise claim to be anyone)

Instead, every trust record traces back to one of two handshake types:

- **Hop-to-hop handshake** — runs directly with an adjacent (single-hop) peer
  over the active transport session.
- **Relayed end-to-end handshake** — when the destination is multiple hops
  away, MeshLink drives the same Noise XX handshake with that destination,
  carried inside opaque handshake frames that intermediate relays forward
  without terminating. The cryptographic guarantees are identical to the
  hop-to-hop case; only the transport differs.

Inbound direct messages are checked against this pre-existing,
handshake-established trust; an envelope can never bootstrap trust for
itself. If no trust is already pinned for a claimed sender, the message is
rejected rather than used to seed a new trust record.

## Mesh domain is cryptographically enforced, too

Every Noise XX handshake also mixes in a hash derived from the local
`appId` as the handshake's prologue, before any key material is exchanged.
Two peers configured with different `appId`s derive different handshake
transcripts and fail authentication, even if a discovery-layer coincidence
(the 16-bit BLE discovery hash) let them reach the handshake stage. Mesh
isolation is therefore a property of the cryptographic protocol, not just of
the discovery filter — see
[About how MeshLink works](about-how-meshlink-works.md#appid-is-a-mesh-boundary-enforced-twice).

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

## Related docs

- [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- [MeshLink runtime behavior reference](../reference/meshlink-runtime-behavior.md)
- [About integrating MeshLink well](about-integrating-meshlink.md)
- [The peer lifecycle model](peer-lifecycle.md)
