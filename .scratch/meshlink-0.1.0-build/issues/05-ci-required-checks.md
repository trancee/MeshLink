# Run the build in CI and make its jobs required on `main`

Status: done
Type: HITL
Blocked by: 02, 03, 04

## Behavior

Every pull request runs the Gradle gates on Linux and the iOS simulator tests and SwiftPM consumer on macOS, and `main` accepts a merge only when those jobs and the existing docs and privacy jobs pass. A scheduled, non-required job builds and tests with the Xcode 27 preview. This completes milestone M0.

## Spec and ADR references

- [ADR 0021](../../../docs/adr/0021-change-workflow-and-merge-gates.md) decision 6 (ruleset on `main`: strict required checks, linear history, squash-only pull requests, no bypass) and decision 7 (ruleset changes need the owner's explicit approval).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "CI" (actions pinned to commit SHAs with version comments; required macOS jobs on `macos-26` with `/Applications/Xcode_26.4.1.app` selected explicitly; scheduled `xcode-27` job with Xcode 27.2; NDK 30.0.16248370 installed with `sdkmanager`) and the 2026-11-08 amendment (pull-request tier; no automatic retries).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 12 (pull requests target at most 15 min) and decision 13.
- [`AGENTS.md`](../../../AGENTS.md) "GIT/EXTERNAL" (latest stable official action release, SHA pin with matching version comment); Constitution G2, G3, E7.

## Acceptance criteria

- [x] A workflow runs on `pull_request` and on pushes to `main`. An `ubuntu-24.04` job runs `./gradlew check` (Android host tests with Robolectric, Kover, detekt, Spotless, ABI, changelog and direction checks) and `./gradlew dokkaGenerate`.
- [x] A `macos-26` job selects `/Applications/Xcode_26.4.1.app` explicitly, prints the selected Xcode version, and runs `./gradlew iosSimulatorArm64Test` and `tools/swiftpm-consumer/test.sh`.
- [x] NDK 30.0.16248370 is installed explicitly with `sdkmanager` in every job that builds Android native code, and the job fails if it is missing. Not applicable yet: no job builds native code. Owner decision 2026-11-14: the criterion moved to ticket 06, which adds the first native build.
- [x] A scheduled, non-required job runs the macOS steps on the `xcode-27` preview image with Xcode 27.2 selected explicitly. The ticket verifies the runner label from GitHub's runner-images documentation.
- [x] Every action is the latest stable official release, pinned to its full commit SHA with a matching version comment, and each new action has a row in the dependency register's CI tools section.
- [x] No step retries a test. Gradle and the Robolectric android-all jars are cached between runs, keyed by the catalog and wrapper.
- [x] `actionlint` reports nothing for every workflow.
- [x] The PR's own run shows each required job under 15 min; the PR records the times.
- [x] Owner-approved, after this PR merges: the `main` ruleset gains the new job names as strict required checks, alongside "Markdown lint, spelling and ADR headers", "Protocol spec" and "No private identifiers in any branch history", with linear history, squash-only pull requests with 0 approvals and no bypass actors. The agent proposes the exact `gh api` call; the owner approves it; the result is read back through the GitHub API and pasted into this ticket.
- [x] A test PR with a deliberately failing Gradle check shows the merge blocked, then is closed.

## Resolution

Done 2026-11-14 in [PR 12](https://github.com/trancee/MeshLink/pull/12) (`c6609d2`).

- Workflow: `.github/workflows/build.yml`. Required jobs: "Gradle check and Dokka (Linux)" (`ubuntu-24.04`) and "iOS simulator tests and SwiftPM consumer (macOS)" (`macos-26`, Xcode 26.4.1, build 17E202). "Xcode 27 preview (scheduled, not required)" (`xcode-27`, Xcode 27.2, build 27B52) runs weekly and on manual dispatch.
- Owner decisions: setup-gradle v6.4.1 with the MIT `basic` cache provider (not the proprietary default); the image's JDK 21 and 25 through `org.gradle.java.installations.fromEnv`; the NDK criterion moved to ticket 06.
- `tools/swiftpm-consumer/test.sh` picks the newest iPhone simulator from `xcodebuild -showdestinations`, so the selected Xcode decides which runtimes are usable.
- Linux checks out full history (`fetch-depth: 0`), because `checkChangelog` diffs against the merge base with `origin/main`.
- Job times, no Gradle cache (PRs only read `main`'s cache): Linux 4 min 14 s and 3 min 35 s; macOS 11 min 53 s, 9 min 13 s and 9 min 1 s. After merge, the dispatched [run 43587881726](https://github.com/trancee/MeshLink/actions/runs/43587881726) passed: Linux 4 min 11 s, macOS 8 min 23 s, Xcode 27 10 min 41 s.
- Ruleset `26807317` updated with owner approval on 2026-11-14 (`gh api -X PUT repos/trancee/MeshLink/rulesets/26807317 --input ruleset-after.json`). Read back with `gh api repos/trancee/MeshLink/rulesets/26807317`:

```json
{
  "bypass_actors": [],
  "conditions": {
    "ref_name": {
      "exclude": [],
      "include": [
        "~DEFAULT_BRANCH"
      ]
    }
  },
  "enforcement": "active",
  "id": 26807317,
  "name": "main: PR with required checks",
  "rules": [
    {
      "type": "non_fast_forward"
    },
    {
      "type": "deletion"
    },
    {
      "type": "required_linear_history"
    },
    {
      "parameters": {
        "allowed_merge_methods": [
          "squash"
        ],
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_extra_approval_for_unattributed_changes": true,
        "require_last_push_approval": false,
        "required_approving_review_count": 0,
        "required_review_thread_resolution": false,
        "required_reviewers": []
      },
      "type": "pull_request"
    },
    {
      "parameters": {
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          {
            "context": "Markdown lint, spelling and ADR headers",
            "integration_id": 15368
          },
          {
            "context": "Protocol spec",
            "integration_id": 15368
          },
          {
            "context": "No private identifiers in any branch history",
            "integration_id": 15368
          },
          {
            "context": "Gradle check and Dokka (Linux)",
            "integration_id": 15368
          },
          {
            "context": "iOS simulator tests and SwiftPM consumer (macOS)",
            "integration_id": 15368
          }
        ],
        "strict_required_status_checks_policy": true
      },
      "type": "required_status_checks"
    }
  ],
  "updated_at": "2026-11-14T12:27:12.326+01:00"
}
```

- Blocked merge: [PR 13](https://github.com/trancee/MeshLink/pull/13) inverted one assertion in `BuildInfoTest`. "Gradle check and Dokka (Linux)" failed (`BuildInfoTest > versionIsTheSemVerReleaseVersion FAILED`); `mergeStateStatus` was `BLOCKED`, and `gh pr merge 13 --squash` answered "the base branch policy prohibits the merge". The PR was closed unmerged and its branch deleted.

## Proof commands

```sh
actionlint
./gradlew check
./gradlew iosSimulatorArm64Test
tools/swiftpm-consumer/test.sh
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
python3 tools/check_private.py
```

## Docs to update

- `PROJECT.md`: CI jobs, hosts and times; the proof matrix gains the authoritative CI host for each row; "Limitations" no longer says the docs checks are the only gates.
- `docs/reference/dependencies.md`: CI tools rows for every new action and for the runner images.
- `docs/adr/0021-change-workflow-and-merge-gates.md`: none, unless the required check names differ from decision 6's list; then a dated amendment.

## Devices needed

None. The owner must approve the ruleset change on GitHub.
