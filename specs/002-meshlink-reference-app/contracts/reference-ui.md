# Contract: Reference App UI Surface

## Purpose

This contract defines the stable operator-facing surfaces the MeshLink reference
app must expose across Android and iOS.

## Surface Catalog

| Surface ID | Audience | Allowed Content | Must Not Contain |
|---|---|---|---|
| `main-guided` | First-time evaluators | Guided first exchange, readiness checks, peer selection, primary send proof, clear recovery guidance | Proof-only behavior, benchmark-only behavior, overwhelming expert controls |
| `advanced-controls` | Integrators and technical reviewers | Full public SDK configuration, runtime controls, deeper technical context, same live proof semantics as the main app | Hidden unsupported SDK capabilities, proof-only transport behavior without lab labeling |
| `technical-timeline` | QA, support, debugging reviewers | Chronological events, filtering, search, peer context, delivery/trust/transfer detail | Unlabeled redaction state, misleading simulated proof |
| `recent-history` | QA, support, regression reviewers | Retained recent sessions, explicit clear/delete controls, reopen-for-review actions | Live-session controls presented as if the retained session were still active |
| `lab` | Internal evaluators needing non-normative context | Proof-only and benchmark-only behavior with explicit non-normative labeling | Any presentation that implies the lab is the default supported product path |
| `solo-exploration` | One-device reviewers | Walkthrough content, static inspection, screen and scenario preview | Simulated outcomes presented as authoritative live evidence |

## Cross-Platform Invariants

- Android and iOS must expose the same surface IDs, names, and operator-facing
  intent.
- Operating-system differences may change setup instructions, but not the named
  workflow catalog or the meaning of success/failure indicators.
- The same MeshLink diagnostic meaning must map to the same timeline language
  on both platforms.

## Workflow Contract

### Guided first exchange

The guided first-exchange workflow must always provide:

1. readiness check result
2. next recommended operator action
3. peer selection or peer-waiting state
4. send action or send proof state
5. timeline evidence for discovery, trust, and delivery

### Advanced controls

The advanced surface must always provide:

1. access to the full public SDK configuration relevant to the app
2. lifecycle controls (`start`, `pause`, `resume`, `stop`)
3. power-mode visibility
4. trust-reset action
5. clear return path to the guided experience

### Lab

The lab surface must always provide:

1. a persistent non-normative label
2. explicit separation from the guided and advanced surfaces
3. a short explanation of why the lab exists
4. no claim that lab behavior replaces the supported product path

### Solo exploration

The solo exploration surface must always provide:

1. a label that it is non-authoritative
2. scenario walkthroughs and screen inspection
3. no live-proof claims for peer discovery, delivery, or diagnostics

## Empty / Blocked / Error States

| Situation | Required UI Response |
|---|---|
| Bluetooth or permission blocked | Show explicit blocker state plus recovery guidance |
| No peer discovered | Keep the guided flow useful and offer solo exploration when appropriate |
| Peer lost during session | Preserve timeline evidence and explain the transition clearly |
| Trust reset or identity change | Explain why the peer can no longer be treated as previously trusted |
| Oversized payload | Reject before send and explain the supported limit |
| Export with full payload disabled | Show default redaction state clearly |

## Acceptance Notes

- Proof-only and benchmark-only behavior must never be reachable without
  crossing into the labeled `lab` surface.
- Recent history must never look like a live session.
- The app must preserve the same operator mental model on Android and iOS.
