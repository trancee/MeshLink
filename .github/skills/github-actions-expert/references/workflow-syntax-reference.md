# Workflow Syntax Quick Reference

Detailed syntax reference for workflow YAML. Use this when the user needs precise syntax details, edge cases, or the full list of available options for a specific key.

## Table of Contents

- [Filter Pattern Cheat Sheet](#filter-pattern-cheat-sheet)
- [All Available Permissions](#all-available-permissions)
- [Shell Configuration](#shell-configuration)
- [defaults.run](#defaultsrun)
- [Matrix Advanced Patterns](#matrix-advanced-patterns)
- [continue-on-error and fail-fast](#continue-on-error-and-fail-fast)
- [Service Container Networking](#service-container-networking)
- [workflow_dispatch Inputs](#workflow_dispatch-inputs)
- [Scheduled Workflows (Cron)](#scheduled-workflows-cron)
- [Job Outputs in Matrix Jobs](#job-outputs-in-matrix-jobs)

---

## Filter Pattern Cheat Sheet

Filters for branches, tags, and paths use glob-like patterns:

| Character | Meaning |
|-----------|---------|
| `*` | Matches any character except `/` |
| `**` | Matches any character including `/` |
| `?` | Matches zero or one of the preceding character |
| `+` | Matches one or more of the preceding character |
| `[abc]` | Matches one of the listed characters |
| `[0-9]` | Matches one character in the range |
| `!` | Negates a pattern (must follow a positive pattern) |

### Branch/tag patterns

| Pattern | Matches | Does not match |
|---------|---------|---------------|
| `feature/*` | `feature/my-branch` | `feature/a/b` |
| `feature/**` | `feature/a`, `feature/a/b/c` | — |
| `'*'` | `main`, `releases` | `all/branches` |
| `v[12].[0-9]+.[0-9]+` | `v1.10.1`, `v2.0.0` | `v3.0.0` |

### Path patterns

| Pattern | Matches |
|---------|---------|
| `'*.js'` | `app.js` (root only) |
| `'**.js'` | `app.js`, `src/lib/app.js` |
| `docs/**` | `docs/README.md`, `docs/a/b.md` |
| `'**/docs/**'` | `docs/hello.md`, `dir/docs/file.txt` |
| `'**/migrate-*.sql'` | `migrate-001.sql`, `db/migrate-v1.sql` |

### YAML quoting rules

Patterns starting with `*`, `[`, or `!` must be quoted in YAML:

```yaml
# Correct
paths:
  - '**/README.md'
  - '!docs/**'

# Wrong — YAML parse error
paths:
  - **/README.md
```

### Negation patterns

Use `!` to exclude after including. Order matters — later patterns override earlier ones:

```yaml
on:
  push:
    branches:
      - 'releases/**'       # Include all release branches
      - '!releases/**-alpha' # Exclude alpha branches
    paths:
      - 'src/**'             # Include all source files
      - '!src/generated/**'  # Exclude generated code
```

---

## All Available Permissions

The full list of `GITHUB_TOKEN` permission scopes. When you specify any permission, all unspecified permissions are set to `none`.

| Permission | What it controls |
|------------|-----------------|
| `actions` | Manage workflow runs (cancel, re-run) |
| `artifact-metadata` | Create storage records for build artifacts |
| `attestations` | Generate artifact attestations for provenance |
| `checks` | Create/update check runs and check suites |
| `contents` | Read repo contents, create releases, manage tags |
| `deployments` | Create/manage deployments |
| `discussions` | Manage GitHub Discussions |
| `id-token` | Fetch OIDC tokens for cloud auth (write-only) |
| `issues` | Create/edit issues and comments |
| `models` | Use GitHub Models inference API (read-only) |
| `packages` | Upload/publish to GitHub Packages |
| `pages` | Trigger GitHub Pages builds |
| `pull-requests` | Create/edit PRs, add labels, post comments |
| `security-events` | Read/update code scanning alerts |
| `statuses` | Create/read commit statuses |

Each permission accepts: `read`, `write`, or `none`. `write` includes `read`. `id-token` only accepts `write` or `none`.

**Special syntax:**
```yaml
permissions: read-all     # Everything read
permissions: write-all    # Everything write
permissions: {}           # Everything none (most restrictive)
```

**Best practice:** Set `permissions: {}` at the workflow level, then grant per-job:
```yaml
permissions: {}

jobs:
  lint:
    permissions:
      contents: read
    # ...
  comment:
    permissions:
      pull-requests: write
    # ...
```

**Fork behavior:** Fork PRs get read-only tokens regardless of `permissions` declaration. Dependabot-triggered workflows also get read-only tokens.

---

## Shell Configuration

The `shell` option controls how `run` steps are executed. Each shell has different error-handling behavior:

| Shell | Platform | Error behavior | Command |
|-------|----------|---------------|---------|
| (default) | Linux/macOS | `bash -e {0}` — exits on first error | |
| `bash` | All | `bash --noprofile --norc -eo pipefail {0}` — also fails on pipe errors | |
| `sh` | Linux/macOS | `sh -e {0}` — exits on first error | |
| `pwsh` | All | PowerShell Core with `$ErrorActionPreference = 'stop'` | |
| `powershell` | Windows | PowerShell Desktop with `$ErrorActionPreference = 'stop'` | |
| `cmd` | Windows | No fail-fast — check `%ERRORLEVEL%` manually | |
| `python` | All | `python {0}` — runs the script as Python | |

**Key difference**: unspecified shell on Linux uses `bash -e {0}` (no pipefail), while explicitly setting `shell: bash` uses `bash --noprofile --norc -eo pipefail {0}` (with pipefail). This matters when piping commands.

**Custom shell**: You can use any installed executable:
```yaml
- shell: perl {0}
  run: print %ENV
```

**Environment variable syntax by platform:**
- Linux/macOS: `$NAME` or `${NAME}`
- Windows PowerShell: `$env:NAME`
- Windows cmd: `%NAME%`

---

## defaults.run

Set default `shell` and `working-directory` for all `run` steps. Useful for monorepos:

**Workflow-level:**
```yaml
defaults:
  run:
    shell: bash
    working-directory: ./backend
```

**Job-level** (overrides workflow-level):
```yaml
jobs:
  test:
    defaults:
      run:
        working-directory: ./packages/api
    steps:
      - run: npm test     # Runs in ./packages/api
      - run: npm run build  # Also runs in ./packages/api
```

Step-level `working-directory` overrides the job default. Note that `defaults.run` only applies to `run:` steps, not `uses:` steps.

---

## Matrix Advanced Patterns

### Object values in matrix variables

Matrix variables can be arrays of objects, not just scalars:

```yaml
strategy:
  matrix:
    include:
      - site: "production"
        datacenter: "site-a"
        deploy_url: "https://prod.example.com"
      - site: "staging"
        datacenter: "site-b"
        deploy_url: "https://staging.example.com"
```

### Adding extra variables to specific combinations with `include`

```yaml
strategy:
  matrix:
    os: [ubuntu-latest, windows-latest]
    node: [18, 20]
    include:
      - os: windows-latest
        node: 20
        npm: 10    # Extra variable only for this combination
```

### Excluding combinations

```yaml
strategy:
  matrix:
    os: [ubuntu-latest, macos-latest, windows-latest]
    version: [12, 14, 16]
    exclude:
      - os: macos-latest
        version: 12   # Skip this specific combination
```

### Matrix limits

- Maximum 256 jobs per matrix per workflow run
- `include` is processed after `exclude`, so you can add back excluded combos

---

## continue-on-error and fail-fast

These two keys interact:

| `fail-fast` | `continue-on-error` | Behavior |
|-------------|--------------------:|----------|
| `true` (default) | `false` (default) | First failure cancels all other matrix jobs |
| `false` | `false` | All jobs run; workflow fails if any job fails |
| `true` | `true` | Other matrix jobs aren't cancelled by this job's failure |
| `false` | `true` | Job failure doesn't affect other jobs or workflow status |

**Common pattern**: Allow experimental versions to fail without cancelling the rest:

```yaml
strategy:
  fail-fast: true
  matrix:
    node: [18, 20]
    experimental: [false]
    include:
      - node: 22
        experimental: true
continue-on-error: ${{ matrix.experimental }}
```

---

## Service Container Networking

The hostname rules depend on whether the job itself runs in a container:

| Job context | Service hostname | Port access |
|-------------|-----------------|-------------|
| `runs-on:` only (no container) | `localhost` | Mapped host port |
| `container:` specified | Service label name (e.g., `postgres`) | Container port directly |

**Random port mapping**: When you don't specify a host port, Docker assigns a random one. Access it via the `job.services` context:

```yaml
services:
  redis:
    image: redis
    ports:
      - 6379/tcp    # Random host port → container 6379

steps:
  - run: echo "Redis on localhost:${{ job.services.redis.ports['6379'] }}"
```

---

## workflow_dispatch Inputs

Manual trigger with typed inputs (max 25 inputs, max 65535 chars total):

```yaml
on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target environment'
        required: true
        type: environment          # Shows environment selector in UI
      log_level:
        description: 'Log level'
        required: true
        default: 'warning'
        type: choice               # Dropdown in UI
        options: [info, warning, debug]
      dry_run:
        description: 'Dry run mode'
        required: false
        type: boolean              # Checkbox in UI
      version:
        description: 'Version to deploy'
        required: true
        type: string               # Free text in UI
```

Access via `inputs` context (preserves types) or `github.event.inputs` (converts to strings):
```yaml
steps:
  - if: inputs.dry_run            # Boolean — use inputs context
    run: echo "Dry run mode"
  - run: echo "Deploying ${{ inputs.version }} to ${{ inputs.environment }}"
```

---

## Scheduled Workflows (Cron)

```
┌───────────── minute (0 - 59)
│ ┌───────────── hour (0 - 23)
│ │ ┌───────────── day of the month (1 - 31)
│ │ │ ┌───────────── month (1 - 12 or JAN-DEC)
│ │ │ │ ┌───────────── day of the week (0 - 6 or SUN-SAT)
│ │ │ │ │
* * * * *
```

| Operator | Example | Meaning |
|----------|---------|---------|
| `*` | `15 * * * *` | Every hour at minute 15 |
| `,` | `0 9,17 * * *` | At 9:00 and 17:00 |
| `-` | `0 9-17 * * *` | Every hour from 9 through 17 |
| `/` | `*/15 * * * *` | Every 15 minutes |

**Common schedules:**
- `0 0 * * *` — daily at midnight UTC
- `0 9 * * 1` — every Monday at 9:00 UTC
- `0 */6 * * *` — every 6 hours
- `30 5 * * 1-5` — weekdays at 5:30 UTC

**Gotchas:**
- Minimum interval is 5 minutes
- Scheduled workflows run on the default branch only
- Schedule times are UTC — no timezone support
- GitHub may delay or skip scheduled runs during high load periods
- Multiple schedules are supported; distinguish them with `github.event.schedule`

---

## Job Outputs in Matrix Jobs

When a matrix job defines outputs, each matrix combination can set the same output name. The last job to complete with a non-empty value wins:

```yaml
jobs:
  build:
    strategy:
      matrix:
        version: [1, 2, 3]
    outputs:
      output_1: ${{ steps.gen.outputs.output_1 }}
      output_2: ${{ steps.gen.outputs.output_2 }}
      output_3: ${{ steps.gen.outputs.output_3 }}
    steps:
      - id: gen
        run: echo "output_${{ matrix.version }}=${{ matrix.version }}" >> "$GITHUB_OUTPUT"

  consume:
    needs: build
    steps:
      - run: echo '${{ toJSON(needs.build.outputs) }}'
      # {"output_1": "1", "output_2": "2", "output_3": "3"}
```

**Important:** Actions does not guarantee the order matrix jobs complete in. Use unique output names per combination to avoid races.
