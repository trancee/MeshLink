# How to run the reference-app physical integration scenarios

Use this guide when you need retained physical evidence from the MeshLink
reference app on real devices.

It focuses on behaviors that simulators and scripted UI tests cannot prove on
their own:

- real peer discovery and trust establishment
- mixed-platform direct delivery
- lifecycle recovery on a live mesh
- trust reset and recovery on a live peer
- large-transfer delivery over real transports
- constrained `A → B → C` routed delivery
- retained redacted export behavior after a live session
- the live-only full-export boundary
- teardown signals such as route retraction and route expiry

If you only need deterministic surface coverage for **Guided first exchange**,
**Advanced controls**, **Technical timeline**, **Lab**, or blocked-start UI
flows, use the scripted Android and iOS workflow tests instead.

## Quick scenario picker

| Use this when you need to prove... | Scenario |
|---|---|
| one clean iPhone ↔ Android exchange with retained redacted export | **Direct guided proof** |
| that pause and resume do not strand the next send | **Direct pause/resume recovery** |
| that live full export and retained redacted export stay separate | **Direct full-export boundary** |
| that `forgetPeer` really clears state and the next send recovers | **Direct trust-reset recovery** |
| that a payload larger than the default hello still arrives | **Direct large transfer** |
| that the first physical iPhone launch can get past Bluetooth prompt friction | **Direct XCTest permission recovery** |
| a real `A = iPhone 15 → B = Samsung → C = OPPO` routed send with `routeIsDirect=false` | **Constrained relay proof** |
| a repeatable multi-scenario campaign with one retained summary | **Physical matrix** |

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

## Direct runner shape

All direct scenarios use the same runner:

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <android-serial-in-direct-range> \
  --ios-device <iphone-15-udid> \
  [extra flags] \
  --run-dir <run-dir>
```

Use these variants.

### Direct guided proof

Fastest end-to-end physical proof.

Extra flags: none

Suggested run dir: `/tmp/reference_direct_guided`

Expected pass signals:

- the iPhone sender reaches `proof.complete role=sender`
- the Android passive peer reaches `proof.complete role=passive`
- the retained export stays redacted by default
- the retained history entry is marked as retained

### Direct XCTest permission recovery

Use this only when the first physical iPhone launch still needs help clearing a
Bluetooth permission prompt.

Extra flags:

```bash
--ios-launch-mode xcuitest
```

Suggested run dir: `/tmp/reference_direct_xcuitest`

Use this as a recovery tool, not as the main physical proof. Once the physical
sender path is permission-clean, go back to the normal direct guided proof.

### Direct pause/resume recovery

Checks that the sender can pause and resume the mesh before the first proof
send.

Extra flags:

```bash
--scenario direct-pause-resume
```

Suggested run dir: `/tmp/reference_direct_pause_resume`

Expected sender-side markers:

- `pause.requested`
- `pause.observed`
- `resume.requested`
- `resume.observed`
- `proof.complete`

### Direct full-export boundary

Checks the live full-export path separately from the retained redacted export
path.

Extra flags:

```bash
--scenario direct-full-export
```

Suggested run dir: `/tmp/reference_direct_full_export`

Expected pass signals:

- the passive peer writes `android_export_full.json`
- that full export records `fullPayloadIncluded=true`
- the retained export still records `operatorOptInRecorded=false`
- the retained export still excludes `fullPayload`

### Direct trust-reset recovery

Checks that `forgetPeer` is an end-to-end recovery path, not just a UI control.

Extra flags:

```bash
--scenario direct-trust-reset-recovery
```

Suggested run dir: `/tmp/reference_direct_trust_reset`

Expected sender-side markers:

- `trust.reset.requested`
- `trust.reset.observed`
- `phase=recovery`
- `proof.complete ... deliveries=2`

### Direct large transfer

Checks that the direct proof path still works with a payload larger than the
default hello.

Extra flags:

```bash
--scenario direct-large-transfer
```

Suggested run dir: `/tmp/reference_direct_large_transfer`

Expected sender-side markers:

- `payload=large-transfer`
- a send request larger than 4 KiB
- `proof.complete ... bytes=<large-payload-size>`

## Constrained relay proof

Checks a real routed send instead of an accidental direct success.

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
do **not** count that as a passing relay proof. That is the false positive this
scenario is meant to prevent.

## Full physical matrix

Runs a selected scenario set and writes one retained directory that shows what
passed, what failed, and where to look next.

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

Two practical notes:

- if your relay placement makes direct adjacency impossible, run the direct and
  relay phases separately with different physical placement; the matrix supports
  that through `--direct-android-serial` and `--scenarios`
- when both Android serials are provided, the direct runner force-stops the
  non-participating Android peer before and after each direct scenario so the
  extra device does not silently contaminate the one-hop proof

When passive-side retention is temporarily blocked but you still need
sender-side physical evidence for lifecycle recovery, trust reset, or large
transfer, add:

```bash
--skip-android-completion-wait
```

That mode is not a full end-to-end pass, but it still gives retained sender
logs for the scenario-specific markers.

## Retained outputs

Each scenario directory writes the raw logs plus these summary artifacts:

- `summary.json` — scenario-specific completion lines and retained export path
- `analysis.json` — machine-readable pass/fail analysis for the scenario
- `analysis.md` — reviewer-friendly explanation of what passed, what failed,
  and what to investigate next

The direct scenario also captures the Android retained history and redacted
export files.

The relay scenario additionally captures:

- the passive Android retained history and redacted export
- the passive Android identity preferences
- the relay Android identity preferences

Use the analysis artifact as the main review surface. The goal is to avoid raw
log scrolling as the primary way to judge whether a physical run was meaningful.

## Hard pass criteria

| Scenario | Hard pass criteria |
|---|---|
| Direct proof | sender completion; passive completion; retained redacted export captured locally; retained history captured locally; no full payload bytes in the redacted export |
| Direct pause/resume recovery | all direct-proof criteria plus sender `pause.requested`, `pause.observed`, `resume.requested`, and `resume.observed` |
| Direct full-export boundary | all direct-proof criteria plus live full export captured locally; full export records operator opt-in and includes full payload content; retained export still remains redacted |
| Direct trust-reset recovery | all direct-proof criteria plus trust reset requested and observed on the sender; a second successful recovery send after the reset; two deliveries observed for the sender-side proof |
| Direct large transfer | all direct-proof criteria plus sender requests the large-transfer payload; the payload is much larger than the inline hello size; the passive side records a large inbound payload when full end-to-end retention is available |
| Relay proof | sender completion; bootstrap before routed send; sender delivery with `routeIsDirect=false`; relay queued-forward and delivered-forward observations; passive completion; no `transport.data.noSession` failure |

Everything else is supporting evidence. Useful, but not a substitute for the
hard criteria above.

## Next step

After the matrix is stable, read
[Physical reference-app integration findings](../explanation/reference-app-physical-integration-findings.md)
for the current lessons, optimizations, and reasons these scenarios are shaped
this way.
