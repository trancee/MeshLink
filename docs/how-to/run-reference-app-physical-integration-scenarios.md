# How to run the reference-app physical integration scenarios

Use this guide when you need retained physical evidence for the MeshLink
reference app, not just scripted UI coverage.

This guide is for the physical-only behaviors that a simulator or scripted UI
surface cannot prove on its own:

- real peer discovery and trust establishment
- mixed-platform direct delivery
- constrained `A → B → C` routed delivery
- retained redacted export behavior after a live session
- teardown side effects such as route retraction and route expiry

If you only need deterministic surface coverage for guided, advanced, timeline,
lab, or blocked-start UI flows, use the scripted Android and iOS workflow tests
instead. Physical scenarios are for transport, routing, and retained-evidence
validation.

## Before you start

Prepare the following first:

- the repository checked out locally
- Xcode with a locally valid Apple development team
- an **iPhone 15** available as the physical sender (`A`)
- at least one Android device available over **wireless ADB**
- for the relay scenario, two Android devices:
  - `B = Samsung` as the relay
  - `C = OPPO` as the passive recipient
- Bluetooth enabled on every device
- first-run Bluetooth prompts already cleared, or willingness to use the
  XCTest sender path once to clear them

Keep the relay topology honest:

- `A` should see only `B` directly
- `C` should see only `B` directly
- `B` must be the only node that can see both sides

If Android or iOS is still blocked on permissions, clear that first with
[How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

## Choose the right scenario

| Scenario | What it proves | Why it exists |
|---|---|---|
| **Direct guided proof** | iPhone sender ↔ one Android passive peer, trust, inbound delivery, retained redacted export | This is the baseline physical proof. If this is not stable, nothing more complex matters. |
| **Direct XCTest permission recovery** | The same direct proof, but through the physical iPhone XCTest launch path | This is the edge-case runner for first-run Bluetooth prompts and other physical iPhone launch friction. |
| **Constrained relay proof** | `A = iPhone 15` sender, `B = Samsung` relay, `C = OPPO` passive recipient, with `routeIsDirect=false` | This proves routing, relay forwarding, temporary-peer promotion, and recipient-side retained export instead of a sender-only false positive. |
| **Physical matrix** | Runs the selected scenarios in order and writes one summary for the full campaign | This keeps physical validation repeatable and makes regressions obvious to reviewers. |

## Run the direct guided proof

Use the stable one-hop proof when you want the fastest end-to-end retained
artifact.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial> \
  --ios-device <iphone-15-udid> \
  --run-dir /tmp/reference_direct_guided
```

Expected pass signals:

- the iPhone sender reaches `proof.complete role=sender`
- the Android passive peer reaches `proof.complete role=passive`
- the retained export stays redacted by default
- the retained history entry is marked as retained

## Run the direct XCTest permission-recovery path

Use this when the first physical iPhone launch still needs help clearing a
Bluetooth permission prompt.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial> \
  --ios-device <iphone-15-udid> \
  --ios-launch-mode xcuitest \
  --run-dir /tmp/reference_direct_xcuitest
```

Use this as a recovery tool, not as the only physical proof. Once the physical
sender path is permission-clean, prefer the normal direct guided proof again.

## Run the constrained relay proof

Use this when you need to prove that the reference app can drive a real routed
send instead of silently succeeding on a direct peer.

```bash
python3 meshlink-reference/scripts/run_headless_reference_relay_proof.py \
  --ios-device <iphone-15-udid> \
  --relay-android-serial <samsung-serial> \
  --passive-android-serial <oppo-serial> \
  --run-dir /tmp/reference_relay_guided
```

Expected pass signals:

- the iPhone sender logs `bootstrap.requested`
- the iPhone sender later reaches `proof.complete role=sender`
- the sender-side routed delivery contains `routeIsDirect=false`
- the Samsung relay logs both `forward.message.queued` and
  `forward.message.delivered`
- the relay shows temporary-peer promotion from a transport-only `bt-...` peer
  to a canonical advertisement peer
- the OPPO passive peer reaches `proof.complete role=passive`
- the OPPO retained export still stays redacted by default

If the sender completes on a direct route and the passive peer never completes,
do **not** count that as a passing relay proof. That is the exact false positive
this scenario is meant to prevent.

## Run the full physical matrix

Use the matrix when you want one retained directory that shows which physical
scenarios passed, which failed, and where to look next.

```bash
python3 meshlink-reference/scripts/run_headless_reference_physical_matrix.py \
  --ios-device <iphone-15-udid> \
  --passive-android-serial <oppo-serial> \
  --relay-android-serial <samsung-serial>
```

By default the matrix runs:

- `direct-guided`
- `relay-constrained`

You can override the selection explicitly:

```bash
python3 meshlink-reference/scripts/run_headless_reference_physical_matrix.py \
  --ios-device <iphone-15-udid> \
  --passive-android-serial <oppo-serial> \
  --relay-android-serial <samsung-serial> \
  --scenarios direct-guided,direct-xcuitest-permission-recovery,relay-constrained
```

## Read the retained outputs

Each scenario directory writes the raw logs plus two summary artifacts:

- `summary.json` — the scenario-specific completion lines and retained export path
- `analysis.json` — machine-readable pass/fail analysis for the scenario
- `analysis.md` — a reviewer-friendly explanation of what passed, what failed,
  and what to investigate next

The direct scenario also captures the Android retained history and redacted
export files.

The relay scenario additionally captures:

- the passive Android retained history and redacted export
- the passive Android identity preferences
- the relay Android identity preferences

Use the analysis artifact as the review surface. The goal is to stop relying on
raw log scrolling to decide whether a physical run was meaningful.

## What counts as a pass

Treat these as hard pass criteria:

### Direct proof

- sender completion
- passive completion
- retained redacted export captured locally
- retained history captured locally
- no full payload bytes in the redacted export

### Relay proof

- sender completion
- bootstrap before routed send
- sender delivery with `routeIsDirect=false`
- relay queued-forward and delivered-forward observations
- passive completion
- no `transport.data.noSession` failure

Everything else is supporting evidence. Useful, but not a substitute for the
hard criteria above.

## Next step

After the matrix is stable, read
[Physical reference-app integration findings](../explanation/reference-app-physical-integration-findings.md)
for the current lessons, optimizations, and reasons these scenarios are shaped
this way.
