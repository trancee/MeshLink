# How to run the reference-app physical integration scenarios

Use this guide when you need retained physical evidence for the MeshLink
reference app, not just scripted UI coverage.

This guide is for the physical-only behaviors that a simulator or scripted UI
surface cannot prove on its own:

- real peer discovery and trust establishment
- mixed-platform direct delivery
- lifecycle recovery on a live mesh
- trust reset and recovery on a live peer
- large-transfer delivery over real transports
- constrained `A → B → C` routed delivery
- retained redacted export behavior after a live session
- full-payload export only while the live session is still authoritative
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
| **Direct pause/resume recovery** | The direct proof after an explicit mesh pause and resume on the sender | This verifies that lifecycle control does not strand a live session or silently break the next send. |
| **Direct full-export boundary** | A live full-payload export before session end, then a retained redacted export after end | This verifies the export-policy boundary instead of assuming it works from the UI contract alone. |
| **Direct trust-reset recovery** | One successful send, a real `forgetPeer`, then a second successful send | This verifies that trust reset actually clears the route and that recovery still works on real devices. |
| **Direct large transfer** | A physically delivered oversized payload rather than the default 19-byte hello | This exercises the transfer path with real radios instead of only the inline hello path. |
| **Direct XCTest permission recovery** | The same direct proof, but through the physical iPhone XCTest launch path | This is the edge-case runner for first-run Bluetooth prompts and other physical iPhone launch friction. |
| **Constrained relay proof** | `A = iPhone 15` sender, `B = Samsung` relay, `C = OPPO` passive recipient, with `routeIsDirect=false` | This proves routing, relay forwarding, and recipient-side retained export instead of a sender-only false positive. Temporary-peer promotion remains a valuable supporting signal when that transport path is exercised. |
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

## Run the direct pause/resume recovery

Use this when you want to prove that the sender can pause and resume the mesh
before the first proof send.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial-in-direct-range> \
  --ios-device <iphone-15-udid> \
  --scenario direct-pause-resume \
  --run-dir /tmp/reference_direct_pause_resume
```

Expected sender-side physical markers:

- `pause.requested`
- `pause.observed`
- `resume.requested`
- `resume.observed`
- `proof.complete`

## Run the direct full-export boundary

Use this when you want to verify the live full-export path separately from the
retained redacted export path.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial-in-direct-range> \
  --ios-device <iphone-15-udid> \
  --scenario direct-full-export \
  --run-dir /tmp/reference_direct_full_export
```

Expected pass signals:

- the passive peer writes `android_export_full.json`
- that full export records `fullPayloadIncluded=true`
- the retained export still records `operatorOptInRecorded=false`
- the retained export still excludes `fullPayload`

## Run the direct trust-reset recovery

Use this when you want to verify that `forgetPeer` is not just a UI control but
an end-to-end recovery path.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial-in-direct-range> \
  --ios-device <iphone-15-udid> \
  --scenario direct-trust-reset-recovery \
  --run-dir /tmp/reference_direct_trust_reset
```

Expected sender-side physical markers:

- `trust.reset.requested`
- `trust.reset.observed`
- `phase=recovery`
- `proof.complete ... deliveries=2`

## Run the direct large transfer

Use this when you want to verify that the direct proof path still works with a
larger payload and not only the default hello.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial-in-direct-range> \
  --ios-device <iphone-15-udid> \
  --scenario direct-large-transfer \
  --run-dir /tmp/reference_direct_large_transfer
```

Expected sender-side physical markers:

- `payload=large-transfer`
- a send request larger than 4 KiB
- `proof.complete ... bytes=<large-payload-size>`

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
- if the relay started on a transport-only `bt-...` peer, it promotes that
  temporary peer to the canonical advertisement peer instead of losing session
  continuity later
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
  --direct-android-serial <android-serial-in-direct-range> \
  --passive-android-serial <oppo-serial> \
  --relay-android-serial <samsung-serial>
```

By default the matrix runs:

- `direct-guided`
- `direct-pause-resume`
- `direct-full-export`
- `direct-trust-reset-recovery`
- `direct-large-transfer`
- `relay-constrained` when both relay Android devices are present

You can override the selection explicitly:

```bash
python3 meshlink-reference/scripts/run_headless_reference_physical_matrix.py \
  --ios-device <iphone-15-udid> \
  --direct-android-serial <android-serial-in-direct-range> \
  --passive-android-serial <oppo-serial> \
  --relay-android-serial <samsung-serial> \
  --scenarios direct-guided,direct-pause-resume,direct-trust-reset-recovery,relay-constrained
```

If your relay placement makes direct adjacency impossible, run the direct and
relay phases separately with different physical placement. The matrix supports
that split explicitly through `--direct-android-serial` and `--scenarios`.

When both Android serials are provided, the direct runner now force-stops the
non-participating Android peer before and after each direct scenario so the
extra device does not silently contaminate the one-hop proof.

When passive-side retention is temporarily blocked but you still need sender-side
physical evidence for lifecycle recovery, trust reset, or large transfer, add
`--skip-android-completion-wait`. That mode is not a full end-to-end pass, but
it still gives retained sender logs for the scenario-specific markers.

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

### Direct pause/resume recovery

- all direct-proof criteria
- sender `pause.requested` and `pause.observed`
- sender `resume.requested` and `resume.observed`

### Direct full-export boundary

- all direct-proof criteria
- live full export captured locally
- full export records operator opt-in and includes full payload content
- retained export still remains redacted

### Direct trust-reset recovery

- all direct-proof criteria
- trust reset requested and observed on the sender
- a second successful recovery send after the reset
- two deliveries observed for the sender-side proof

### Direct large transfer

- all direct-proof criteria
- sender requests the large-transfer payload
- the payload is larger than the inline hello size by a wide margin
- the passive side records a large inbound payload when full end-to-end retention is available

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
