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
| the one-command retained happy-path release-review campaign, without hand-picking serials or UDIDs | **Release happy-path campaign** |
| one clean iPhone ↔ Android exchange with retained redacted export | **Direct guided proof** |
| that pause and resume do not strand the next send | **Direct pause/resume recovery** |
| that live full export and retained redacted export stay separate | **Direct full-export boundary** |
| that `forgetPeer` really clears state and the next send recovers | **Direct trust-reset recovery** |
| that the sender restarts and the next send recovers | **Direct restart recovery** |
| that an intentional device isolation still allows the route to recover | **Direct isolation recovery** |
| that a broken route can re-form without a false green | **Direct route-break recovery** |
| that a payload larger than the default hello still arrives | **Direct large transfer** |
| that the first physical iPhone launch can get past Bluetooth prompt friction | **Direct XCTest permission recovery** |
| a real `A = iPhone 15 → B = Samsung → C = OPPO` routed send with `routeIsDirect=false` | **Constrained relay proof** |
| an explicit manual multi-scenario matrix with custom scenario selection and one retained summary | **Physical matrix** |

## Before you start

Prepare the following first:

- the repository checked out locally
- at least one Android device available over **wireless ADB**
- for the preferred mixed baseline, Xcode with a locally valid Apple
  development team plus an **iPhone 15** available as the physical sender (`A`)
- for the Android-only fallback baseline, two healthy Android devices
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

## Release happy-path campaign

Use the fleet-aware release campaign as the default retained happy-path
release-review entrypoint. It discovers the available Android fleet and
optional iOS sender, writes `fleet-manifest.json`, `campaign-plan.json`, and
`campaign-state.json`, plans the ordered logical happy-path catalog, and then
executes every runnable scenario in order. Reviewers can inspect the retained
JSON and per-scenario `analysis.md` artifacts instead of reconstructing the
result from raw logs.

```bash
python3 meshlink-reference/scripts/run_reference_release_campaign.py \
  [--run-root /tmp/reference_release_campaign_review] \
  [--child-timeout-seconds 1800]
```

### Ordered happy-path scenarios

The campaign always plans the same logical scenarios in manifest order:

1. `direct-guided` — always first. This is the compatibility view over the
   selected direct baseline.
2. `relay-constrained` — always second. It becomes runnable only when the
   retained fleet truth includes one healthy iOS sender, two healthy Android
   devices, `adb`, `xcrun devicectl`, and a resolved `DEVELOPMENT_TEAM`.

Within `direct-guided`, the campaign preserves the existing direct-baseline
selection order:

1. `direct-guided-mixed` — preferred mixed baseline with an iOS sender and an
   Android passive peer.
2. `direct-guided-android-only` — fallback baseline with an Android sender and
   a different Android passive peer.

Use the relay eligibility distinction this way:

- `relay-constrained` becomes `skipped` when the fleet shape is honestly too
  small for relay, such as no iOS sender or fewer than two healthy Android
  devices.
- `relay-constrained` becomes `invalid-environment` when the fleet should be
  runnable but tooling, provisioning, or device health is broken, or when
  `direct-guided` itself could not be planned honestly from the retained
  manifest.

### Direct baseline compatibility

Earlier release-review tooling can keep reading the direct compatibility
surfaces:

- `campaign-plan.json.selectedBaseline`
- `fleet-manifest.json.campaign.baselineExecution`

Both are projections of the logical `direct-guided` scenario. The direct
scenario keeps writing under `baseline/<assignmentId>/`, where
`<assignmentId>` is `direct-guided-mixed` or `direct-guided-android-only`.
Later happy-path scenarios live under stable `scenarios/<order>-<scenarioId>/`
directories such as `scenarios/02-relay-constrained/`.

### Honest status taxonomy

The campaign keeps the status vocabulary explicit instead of pretending every
non-pass is the same problem.

| Surface | Values | Meaning |
|---|---|---|
| `candidateAssignments[].status` in `fleet-manifest.json` | `runnable`, `skipped`, `invalid-environment` | whether each direct-baseline candidate was runnable, merely absent for this fleet shape, or blocked by unhealthy tooling or devices |
| `selection.status` in `fleet-manifest.json` and `campaign-plan.json` | `selected`, `skipped`, `invalid-environment` | whether the campaign chose a direct compatibility baseline, honestly skipped because the fleet shape was insufficient, or stopped because the environment was unhealthy |
| `scenarios[].eligibilityStatus` in `campaign-plan.json` and `campaign-state.json` | `runnable`, `skipped`, `invalid-environment` | whether each logical happy-path scenario was eligible to execute |
| `scenarios[].status` in `campaign-state.json` | `planned`, `running`, `pass`, `fail`, `skipped`, `invalid-environment` | the retained per-scenario lifecycle and final outcome |
| `campaign.status` and `campaign.baselineExecution.status` | `planned`, `running`, `pass`, `fail`, `skipped`, `invalid-environment` | the aggregate campaign lifecycle and the direct-compatibility projection |
| `happyPathGate.status` in `campaign-state.json` | `green`, `red` | whether any executed happy-path scenario has gone non-pass |

Use the distinction this way:

- `skipped` means the discovered fleet shape is honestly too small for that
  scenario, but the environment is not claiming to be broken. Example:
  `direct-guided` passes with an Android-only fleet, while
  `relay-constrained` stays `skipped` because no iOS sender exists.
- `invalid-environment` means the workstation, tooling, provisioning, or device
  health is broken enough that the campaign cannot honestly treat the result as
  a simple fleet-shape skip. Examples: missing `adb`, missing `devicectl`, an
  unresolved `DEVELOPMENT_TEAM`, or discovered devices that are present but not
  healthy enough to satisfy the planned roles.
- `fail` means a scenario was executed, but the retained `summary.json`,
  `analysis.json`, or `analysis.md` evidence did not support a passing result.
  Generic wrapper noise such as `xcodebuild` or `build failed` does not upgrade
  that result to `invalid-environment`; only missing tools or an explicit
  environment sentinel from the child/analyzer should do that.

### Red-gate semantics

`campaign-state.json` is the campaign execution ledger. Its
`happyPathGate.status` starts `green`, flips to `red` on the first executed
happy-path scenario that finishes anything other than `pass`, records that
scenario in `firstFailScenarioId`, and stays red for the rest of the campaign
while later runnable scenarios continue and retain evidence. If the campaign
never dispatches a child runner because everything is skipped or planning fails
up front, the gate can remain `green` even though `campaign.status` still ends
as `skipped` or `invalid-environment`.

### Exit codes

| Exit code | Status | Meaning |
|---|---|---|
| `0` | `pass` | every executed happy-path scenario passed, and any non-runnable scenarios remained honest `skipped` entries |
| `1` | `fail` | at least one executed happy-path scenario retained non-passing evidence |
| `2` | `skipped` | no happy-path scenario was runnable for the discovered fleet shape |
| `3` | `invalid-environment` | discovery, tooling, provisioning, or device health blocked honest planning or execution |

### Reviewer inspection flow

Read the retained artifacts in this order:

1. `campaign-plan.json` — planned scenario order, eligibility decisions,
   `selectedBaseline`, and artifact locations.
2. `campaign-state.json` — actual scenario statuses, `happyPathGate`,
   `firstFailScenarioId`, child/analyzer exit codes, and per-scenario
   `eventHistory`.
3. `report-data.json` — aggregated verdict counts, gate math, run
   classification, and scenario-level inspection pointers. Use its verdict
   taxonomy to distinguish `pass`, `fail`, `skipped`, `inconclusive`, and
   `invalid-environment` instead of reconstructing outcomes from raw logs.
4. `release-review-report.html` — the offline reviewer surface that renders the
   retained report data into a self-contained HTML view with drill-downs and
   retained evidence links.
5. per-scenario `analysis.md` under `baseline/...` or `scenarios/...` — the
   first human-readable artifact for why a scenario passed or failed.
6. `fleet-manifest.json` — raw device discovery, candidate assignment
   reasoning, and the full `campaignLog` trail.

`report-data.json` is the next stop after the state ledger when you need a
machine-readable answer to “what actually happened?” The HTML report follows it
as the reviewer-facing artifact: it reads only the retained report data, stays
aligned with the campaign state, and captures the honest verdict taxonomy even
when a run exits early, skips scenarios, or only completes partially.

Contract: inspect `campaign-plan.json`, then `campaign-state.json`, then
`report-data.json`, and only then open `release-review-report.html`. The HTML
renderer consumes retained `report-data.json` only; live devices, live logs,
and reviewer-time discovery are not part of rendering.

```text
<run-root>/
  fleet-manifest.json
  campaign-plan.json
  campaign-state.json
  baseline/
    direct-guided-mixed|direct-guided-android-only/
      summary.json
      analysis.json
      analysis.md
      runner.stdout.log
      runner.stderr.log
      analysis.stdout.log
      analysis.stderr.log
  scenarios/
    02-relay-constrained/
      summary.json
      analysis.json
      analysis.md
      runner.stdout.log
      runner.stderr.log
      analysis.stdout.log
      analysis.stderr.log
```

The direct compatibility directory always matches the retained direct
assignment id. `analysis.md` is still the best first artifact for a reviewer,
while `summary.json` and `analysis.json` remain the machine-readable surfaces
that later slices can extend without changing the story.

Everything below is lower-level manual control. These commands do not maintain
campaign-wide `happyPathGate` or the aggregated `campaign-state.json` ledger;
use them when you need explicit device control, scenario-specific debugging, or
manual direct, relay, and matrix investigations.

## Manual direct runner shape

All direct scenarios below use the explicit mixed-platform runner:

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

## Retained outputs for manual scenarios

Each manual scenario directory writes the raw logs plus these summary artifacts:

- `summary.json` — scenario-specific completion lines and retained export path
- `analysis.json` — machine-readable pass/fail analysis for the scenario
- `analysis.md` — reviewer-friendly explanation of what passed, what failed,
  and what to investigate next
- `release-review-report.html` — the offline HTML review surface for the whole
  campaign run, generated from retained `report-data.json`

The direct scenario also captures the Android retained history and redacted
export files.

The relay scenario additionally captures:

- the passive Android retained history and redacted export
- the passive Android identity preferences
- the relay Android identity preferences

## Recovery-window analysis contract

The analyzer now publishes a small recovery-window record in `analysis.json`
and mirrors it in `analysis.md` so reviewers can see whether a resilience run
recovered in time without re-reading raw logs.

The key fields are:

- `recovery_window.verdict` — `within-window`, `late-recovery`,
  `missing-evidence`, or `not-applicable`
- `recovery_window.injection_requested_line`,
  `recovery_window.injection_observed_line`,
  `recovery_window.recovery_marker_line`, and
  `recovery_window.completion_line` — the exact retained lines that bracket
  the recovery window
- `topology_evidence.pointers` — the retained lines that prove the topology
  change, route retraction, or route-expiry signal when the run emits one

For the current direct resilience scenarios, look for these markers:

- `direct-pause-resume` — `pause.requested`, `pause.observed`,
  `pause.recovered`, `resume.requested`, `resume.observed`
- `direct-trust-reset-recovery` — `trust.reset.requested`,
  `trust.reset.observed`, `trust.reset.recovered`, plus `ROUTE_RETRACTED`
  when the retained route teardown appears
- `direct-restart-recovery`, `direct-isolation-recovery`, and
  `direct-route-break-recovery` — retained scenario names are already wired,
  but the analyzer will honestly report `missing-evidence` until dedicated
  recovery markers are present in the run

Use the analysis artifact as the main review surface. The goal is to avoid raw
log scrolling as the primary way to judge whether a physical run was meaningful.

## Hard pass criteria

| Scenario | Hard pass criteria |
|---|---|
| Direct proof | sender completion; passive completion; retained redacted export captured locally; retained history captured locally; no full payload bytes in the redacted export |
| Direct pause/resume recovery | all direct-proof criteria plus sender `pause.requested`, `pause.observed`, `resume.requested`, and `resume.observed` |
| Direct full-export boundary | all direct-proof criteria plus live full export captured locally; full export records operator opt-in and includes full payload content; retained export still remains redacted |
| Direct trust-reset recovery | all direct-proof criteria plus trust reset requested and observed on the sender, a `trust.reset.recovered` close marker, a second successful recovery send after the reset, and two deliveries observed for the sender-side proof |
| Direct restart recovery | all direct-proof criteria plus the restart scenario’s retained injection and recovery markers; the run stays honest only when `recovery_window.verdict` is `within-window` |
| Direct isolation recovery | all direct-proof criteria plus the isolation scenario’s retained injection and recovery markers; the run stays honest only when `recovery_window.verdict` is `within-window` |
| Direct route-break recovery | all direct-proof criteria plus the route-break scenario’s retained injection and recovery markers; the run stays honest only when `recovery_window.verdict` is `within-window` |
| Direct large transfer | all direct-proof criteria plus sender requests the large-transfer payload; the payload is much larger than the inline hello size; the passive side records a large inbound payload when full end-to-end retention is available |
| Relay proof | sender completion; bootstrap before routed send; sender delivery with `routeIsDirect=false`; relay queued-forward and delivered-forward observations; passive completion; no `transport.data.noSession` failure |

Everything else is supporting evidence. Useful, but not a substitute for the
hard criteria above.

## Next step

After the matrix is stable, read
[Physical reference-app integration findings](../explanation/reference-app-physical-integration-findings.md)
for the current lessons, optimizations, and reasons these scenarios are shaped
this way.
