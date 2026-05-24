# Physical reference-app integration findings

This page explains what the retained physical reference-app runs taught us, why
we shaped the physical scenario matrix the way we did, and which optimizations
matter most for keeping MeshLink stable and easy to evaluate.

## Why physical scenarios still matter

The scripted Android and iOS workflow tests already give strong coverage for the
reference-app surfaces:

- guided flow labels and navigation
- advanced controls
- timeline filtering and export UI
- blocked-start guidance
- solo-mode behavior

But physical runs prove a different class of behavior:

- whether nearby peers are actually discovered on real radios
- whether trust and route state converge across platforms
- whether a routed send is truly routed instead of accidentally direct
- whether retained redacted artifacts survive a real session end
- whether teardown side effects make the next failure diagnosable

That is why the physical matrix is intentionally smaller than the full scripted
surface matrix. It only covers the behaviors that become meaningful when real
devices, real Bluetooth stacks, and real route convergence are involved.

## What the direct proof taught us

The direct guided proof is the baseline physical scenario.

When it passes, we know the following stack is healthy enough to trust more
complex work:

- the iPhone sender can discover an Android peer
- a first trust establishment can complete on real hardware
- one-hop message delivery works on mixed iOS ↔ Android transport paths
- the passive Android peer can retain the session and export a redacted artifact
- the retained artifact still enforces the redaction policy by default

The direct runs also exposed one subtle but important resilience signal on the
sender side: the iPhone can keep a GATT notify side link alive after the L2CAP
channel closes. That is not the primary success criterion, but it is a useful
indicator that the mixed-bearer posture is doing real work rather than existing
only on paper.

## What the early relay failures taught us

The constrained relay proof was much more valuable than a simple “did a send
succeed” check, because it exposed failure modes that a sender-only view would
have hidden.

### 1. Sender-only success can be a false positive

Some early relay attempts reached sender-side `proof.complete` even though the
send never became a real `A → B → C` routed proof.

The failure pattern was:

- the sender found the middle hop directly
- the sender issued a send before the final routed target had converged
- the sender completed on a direct route
- the passive recipient never completed or exported anything

This taught us that a relay proof must never pass on sender completion alone.
The hard invariant is `routeIsDirect=false` plus passive completion.

### 2. Routed targeting must use the canonical advertisement peer ID

The routed recipient in physical automation is not “the first peer that looks
reachable right now”. It is the canonical 24-hex advertisement peer ID for the
intended passive recipient.

Without that discipline, relay automation drifts toward whichever direct peer is
currently available and produces misleading green runs.

That is why the relay runner now derives the passive peer ID from the device’s
stored identity material instead of trusting UI order.

### 3. Bootstrap is sometimes required before the final routed send

Physical routed proofs do not always converge in one jump.

In practice, the sender may need to bootstrap the middle hop first, then wait
for the final route advertisement to the passive recipient, and only then send
the real proof message.

This is not a workaround to hide instability. It is the honest model of what a
cold routed mesh needs to do on real devices.

### 4. Temporary-peer promotion is load-bearing

One of the most revealing relay failures surfaced as `transport.data.noSession`
on the middle hop.

The failure sequence was:

- the relay accepted a transport connection on a temporary peer ID such as
  `bt-...`
- the runtime later learned the canonical advertisement peer ID
- later inbound delivery still arrived on the transport seam
- session lookup no longer resolved the established session cleanly

The fix was not cosmetic. The transport/session seam needed to keep alias
lookups valid after promotion from temporary peer IDs to canonical advertisement
peer IDs.

That is why the relay scenario keeps temporary-peer promotion as a valuable
observability signal, not just a debug curiosity. It is extremely useful when a
transport path begins on a temporary peer ID, but a successful routed proof does
not require that every run exercise that exact path.

## What the successful relay proof now proves

A good constrained relay run proves much more than “a message eventually moved”.
It proves an ordered chain of real behaviors:

1. the passive recipient and relay both start cleanly
2. the sender bootstraps the relay when the final target is not yet routable
3. the final route to the passive recipient appears with `routeIsDirect=false`
4. the sender completes on that routed target rather than on the relay itself
5. the relay logs both queueing and forwarding delivery work
6. the passive recipient receives the inbound message
7. the passive recipient retains the session and exports a redacted artifact
8. teardown emits route retraction or route expiry signals instead of silently
   leaving stale state behind

That is the level of evidence we want because it narrows the next debugging step
immediately when something regresses.

## The optimizations worth keeping

These are the changes that most improved reliability or reviewer confidence.

### Make scenario-specific analysis a first-class artifact

Raw log files are necessary, but they are not the right review surface.

Each retained physical run should produce a small analysis artifact that answers
three questions directly:

- what scenario was this trying to prove?
- which hard invariants were observed?
- what is the most likely next debugging direction if it failed?

This turns physical validation from log archaeology into a repeatable check.

### Fail relay runs on routed invariants, not on sender optimism

If a relay run lacks `routeIsDirect=false`, middle-hop forwarding, or passive
completion, it should fail even if the sender looked happy.

That rule is what prevents the matrix from drifting back toward false positives.

### Keep bootstrap explicit instead of magical

The sender’s bootstrap send is operationally important. It should stay explicit
in logs and in analysis output so reviewers can tell whether a routed proof
needed warm-up or reached the passive target directly.

### Preserve teardown diagnostics

`ROUTE_RETRACTED` and `ROUTE_EXPIRED` are not vanity logs. They tell us whether
post-proof cleanup behaved honestly.

When they disappear, the next failure becomes much harder to diagnose because
stale route state and fresh route state look the same from the outside.

## The optimizations still worth pursuing

These are the next improvements that would make the library easier to operate.

### Reduce connection-attempt log noise during cold convergence

Some passive-device logs still show repeated “no active link, triggering
connect” messages while a connection is already converging.

That is useful during deep transport debugging, but noisy for routine retained
proof review. A coalesced or rate-limited variant would make the logs easier to
read without losing the signal.

### Keep route-target derivation automatic

The more the runners can derive the passive target from retained identity data,
the less they depend on manual copy-paste or UI ordering. That directly improves
repeatability.

### Prefer structured relay diagnostics over ad-hoc text parsing

The current analysis still depends on stable log text. That is much better than
manual review, but the next step would be even stronger: emit a small structured
relay-proof summary directly from the runtime or automation layer.

## What should stay out of the physical matrix

Do not force every UI feature into a physical-device scenario.

That would make the matrix slower, less stable, and harder to interpret.
Instead:

- keep physical scenarios focused on transport, routing, trust, and retained
  artifact behavior
- keep scripted automation responsible for solo mode, blocked-start guidance,
  advanced surface layout, and lab/UI-only behavior

That split is not a compromise. It is what makes the validation story honest and
maintainable.
