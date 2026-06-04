# Physical reference-app integration findings

This page summarizes what recent retained physical reference-app runs taught us,
why the physical scenario matrix is shaped the way it is, and which
optimizations are worth keeping or pursuing.

## Quick takeaways

| Area | What we learned |
|---|---|
| direct proof | it is still the baseline physical signal and must stay clean before deeper scenarios matter |
| direct vs relay placement | they often need different room geometry; running them back-to-back without changing placement can create fake failures |
| direct passive automation | moving it off the UI surface closed a misleading blind spot |
| remaining direct failures | pause/resume recovery and large-transfer proof still expose real sender/runtime blockers |
| relay proof | sender-only success is not enough; `routeIsDirect=false` plus passive completion is the real invariant |
| relay identity handling | canonical advertisement peer IDs and temporary-peer promotion are both load-bearing |
| retained review surface | per-run analysis artifacts, the browser/runtime proof note, and the fleet-test history HTML are much easier to review than raw log scrolling |

## Current milestone outcome

Milestone **M001** validated the release-review campaign end-to-end: one-command fleet discovery, ordered happy-path execution, retained report-data persistence, and the offline HTML review surface all passed on a real 4-device discovered fleet. That closed the validation-surface gap for **R010** by adding retained browser/runtime proof for the offline review path, so reviewers can trust the disk-backed surface without reconstructing raw logs. The campaign now treats Android-only sender symmetry as a deliberate follow-up rather than a hidden assumption.

## Why physical scenarios still matter

The scripted Android and iOS workflow tests already cover a lot of the
reference-app surface:

- **Guided first exchange** labels and navigation
- **Advanced controls**
- **Technical timeline** filtering and export UI
- blocked-start guidance
- **Solo exploration** behavior

Physical runs prove a different class of behavior:

- whether nearby peers are actually discovered on real radios
- whether trust and route state converge across platforms
- whether lifecycle controls still allow the next send to recover
- whether a trust reset really clears and rebuilds peer state
- whether larger payloads still move on real transports
- whether a routed send is truly routed instead of accidentally direct
- whether retained redacted artifacts survive a real session end
- whether teardown signals make the next failure diagnosable

That is why the physical matrix is intentionally smaller than the full scripted
surface matrix. It covers only the behaviors that become meaningful when real
devices, real Bluetooth stacks, and real route convergence are involved.

## What the direct proof taught us

The direct guided proof is the baseline physical scenario.

When it passes, we know:

- the iPhone sender can discover an Android peer
- first trust establishment can complete on real hardware
- one-hop message delivery works on mixed iOS ↔ Android transport paths
- the passive Android peer can retain the session and export a redacted artifact
- the retained artifact still enforces the redaction policy by default

The direct runs also exposed one secondary resilience signal on the sender
side: the iPhone can keep a GATT-notify side link alive after the L2CAP channel
closes. That is not the primary pass criterion, but it shows that the mixed
bearer posture is doing real work.

## What the expanded direct scenarios taught us

The direct baseline is necessary, but not sufficient on its own.

### 1. Direct and relay proofs usually need different placement

A direct proof wants one Android peer in clear range of the iPhone. A
constrained relay proof wants the passive recipient out of direct range.

Trying to run both phases back-to-back without changing placement creates a fake
failure: the one-hop direct phase inherits the relay topology and never
converges.

That is why the matrix now supports a separate `--direct-android-serial` and
why the docs treat direct and relay placement as separate phases when needed.

### 2. Non-participating Android peers must be force-stopped

One extra Android device can silently contaminate a supposedly direct proof.

Typical symptoms are:

- extra peers appear on the sender or passive side
- route updates reflect the wrong topology
- a "direct" result stops meaning direct

That is why the direct runner now force-stops the non-participating Android
peer when the matrix knows about it.

### 3. Moving passive automation off the UI surface fixed the direct blind spot

The direct passive automation used to live behind the app UI lifecycle. On the
current Samsung direct-passive setup, that created a misleading failure mode:

- the Android runtime logged real inbound delivery
- the shared session state contained the inbound evidence
- but the passive proof orchestration never advanced to end/export/proof-complete

Moving the live-proof automation onto the shared session and timeline flows
fixed that blind spot. After the change:

- `direct-guided` passed end-to-end again on iPhone 15 → Samsung
- `direct-trust-reset-recovery` passed end-to-end with `deliveries=2`
- the passive side reached retained export without depending on Compose
  recomposition timing

That narrowed the remaining direct failures to sender and runtime behavior
instead of passive UI orchestration.

### 4. Two direct scenarios still expose real runtime blockers

After the off-UI automation fix, two advanced direct scenarios still fail on
the current iPhone 15 → Samsung setup:

- **Pause/resume recovery** — the warmup send succeeds, but the post-resume
  recovery send still expires as `SendResult.NotSent(UNREACHABLE)`
- **Large transfer** — the first 13,824-byte send never establishes a route on
  the sender and expires as `SendResult.NotSent(UNREACHABLE)` before the
  passive side receives anything

These are no longer passive-automation problems. They are now honest transport
or runtime recovery problems worth debugging in the sender/runtime surface.

## What the relay failures taught us

The constrained relay proof turned out to be much more valuable than a simple
"did a send succeed" check.

### 1. Sender-only success can be a false positive

Some early relay attempts reached sender-side `proof.complete` even though the
send never became a real `A → B → C` routed proof.

The failure pattern was:

- the sender found the middle hop directly
- the sender issued a send before the final routed target had converged
- the sender completed on a direct route
- the passive recipient never completed or exported anything

That is why relay proof must never pass on sender completion alone. The hard
invariant is `routeIsDirect=false` plus passive completion.

### 2. Routed targeting must use the canonical advertisement peer ID

The routed recipient in physical automation is not "the first peer that looks
reachable right now". It is the canonical 24-hex advertisement peer ID for the
intended passive recipient.

Without that discipline, relay automation drifts toward whichever direct peer is
currently available and produces misleading green runs.

That is why the relay runner now derives the passive peer ID from retained
identity material instead of trusting UI order.

### 3. Bootstrap is sometimes required before the final routed send

Physical routed proofs do not always converge in one jump.

In practice, the sender may need to bootstrap the middle hop first, then wait
for the final route advertisement to the passive recipient, and only then send
the real proof message.

That is not a workaround to hide instability. It is the honest model of what a
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
lookups valid after promotion from temporary peer IDs to canonical
advertisement peer IDs.

That is why the relay scenario keeps temporary-peer promotion as a valuable
observability signal. It is especially useful when a transport path begins on a
temporary peer ID, even though a successful routed proof does not require every
run to exercise that exact path.

## What a successful relay proof now proves

A good constrained relay run proves an ordered chain of real behaviors:

1. the passive recipient and relay both start cleanly
2. the sender bootstraps the relay when the final target is not yet routable
3. the final route to the passive recipient appears with `routeIsDirect=false`
4. the sender completes on that routed target rather than on the relay itself
5. the relay logs both queueing and forwarding delivery work
6. the passive recipient receives the inbound message
7. the passive recipient retains the session and exports a redacted artifact
8. teardown emits route retraction or route expiry signals instead of silently
   leaving stale state behind

That level of evidence makes the next debugging step much clearer when
something regresses.

## Optimizations worth keeping

| Keep this | Why |
|---|---|
| scenario-specific `analysis.json` and `analysis.md` artifacts | they turn physical validation from log archaeology into a repeatable review surface |
| failing relay runs on routed invariants, not sender optimism | this prevents the matrix from drifting back toward false positives |
| explicit bootstrap markers | reviewers can tell whether a routed proof needed warm-up or reached the passive target directly |
| explicit direct-scenario isolation | force-stopping non-participating Android peers removes a whole class of accidental false results |
| teardown diagnostics such as `ROUTE_RETRACTED` and `ROUTE_EXPIRED` | without them, stale route state and fresh route state are much harder to distinguish |

## Optimizations still worth pursuing

| Next improvement | Why it matters |
|---|---|
| reduce connection-attempt log noise during cold convergence | routine retained-proof review becomes easier without losing important transport signals |
| keep route-target derivation automatic | reduces manual copy-paste and improves repeatability |
| prefer structured diagnostics over ad-hoc text parsing | makes scenario analysis less dependent on stable log text |
| keep hardening sender-side recovery scenarios | the remaining direct failures are now honest sender/runtime issues, not passive automation problems |

## What should stay out of the physical matrix

Do not force every UI feature into a physical-device scenario.

That would make the matrix slower, less stable, and harder to interpret.
Instead:

- keep physical scenarios focused on transport, routing, trust, and retained
  artifact behavior
- keep scripted automation responsible for **Solo exploration**,
  blocked-start guidance, **Advanced controls** layout, and **Lab** or
  UI-only behavior

## Related docs

- [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md)
- [How to run the reference-app physical integration scenarios](../how-to/run-reference-app-physical-integration-scenarios.md)
- [MeshLink reference app overview](../../meshlink-reference/README.md)
- [Contributor build, test, and verification reference](../reference/contributor-reference.md)

That split is not a compromise. It is what makes the validation story honest
and maintainable.
