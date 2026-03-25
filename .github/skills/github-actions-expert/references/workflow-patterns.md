# Workflow Patterns Reference

Complete, copy-pasteable workflow examples for common CI/CD scenarios. Each pattern follows security best practices (pinned SHAs, minimal permissions, proper secret handling).

> **Note on action SHAs**: The examples below use `@<sha>` as a placeholder. When implementing, replace with the actual full commit SHA from the action's releases page. Use Dependabot to keep them current.

## Table of Contents

- [Node.js CI](#nodejs-ci)
- [Python CI](#python-ci)
- [Go CI](#go-ci)
- [Rust CI](#rust-ci)
- [Docker build and push to GHCR](#docker-build-and-push-to-ghcr)
- [Release automation](#release-automation)
- [Deploy with environment protection](#deploy-with-environment-protection)
- [Monorepo path filtering](#monorepo-path-filtering)
- [Reusable CI workflow](#reusable-ci-workflow)
- [Scheduled dependency audit](#scheduled-dependency-audit)
- [PR labeler and auto-assign](#pr-labeler-and-auto-assign)
- [Composite action example](#composite-action-example)

---

## Node.js CI

Full CI pipeline with caching, linting, testing across Node versions, and coverage upload.

```yaml
name: Node.js CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-node@<sha>
        with:
          node-version: 20
          cache: 'npm'
      - run: npm ci
      - run: npm run lint

  test:
    needs: lint
    strategy:
      matrix:
        node: [18, 20, 22]
        os: [ubuntu-latest]
      fail-fast: false
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-node@<sha>
        with:
          node-version: ${{ matrix.node }}
          cache: 'npm'
      - run: npm ci
      - run: npm test -- --coverage
      - uses: actions/upload-artifact@<sha>
        if: matrix.node == 20
        with:
          name: coverage-report
          path: coverage/
          retention-days: 7
```

## Python CI

```yaml
name: Python CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  test:
    strategy:
      matrix:
        python: ['3.11', '3.12', '3.13']
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-python@<sha>
        with:
          python-version: ${{ matrix.python }}
          cache: 'pip'
      - run: pip install -r requirements.txt -r requirements-dev.txt
      - run: pytest --cov=src --cov-report=xml
      - run: ruff check src/
```

## Go CI

```yaml
name: Go CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-go@<sha>
        with:
          go-version-file: go.mod
          cache: true
      - run: go vet ./...
      - run: go test -race -coverprofile=coverage.out ./...
      - run: go build ./...
```

## Rust CI

```yaml
name: Rust CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/cache@<sha>
        with:
          path: |
            ~/.cargo/bin/
            ~/.cargo/registry/index/
            ~/.cargo/registry/cache/
            ~/.cargo/git/db/
            target/
          key: ${{ runner.os }}-cargo-${{ hashFiles('**/Cargo.lock') }}
          restore-keys: ${{ runner.os }}-cargo-
      - run: cargo fmt --check
      - run: cargo clippy -- -D warnings
      - run: cargo test
```

## Docker build and push to GHCR

```yaml
name: Docker

on:
  push:
    branches: [main]
    tags: ['v*']

permissions:
  contents: read
  packages: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>

      - uses: docker/setup-buildx-action@<sha>

      - uses: docker/login-action@<sha>
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/metadata-action@<sha>
        id: meta
        with:
          images: ghcr.io/${{ github.repository }}
          tags: |
            type=ref,event=branch
            type=semver,pattern={{version}}
            type=sha

      - uses: docker/build-push-action@<sha>
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

## Release automation

Triggered when a tag is pushed. Builds artifacts and creates a GitHub release.

```yaml
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
        with:
          fetch-depth: 0    # Full history for changelog generation

      - name: Build
        run: make build

      - name: Generate changelog
        id: changelog
        run: |
          # Generate changelog from commits since last tag
          PREV_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")
          if [ -n "$PREV_TAG" ]; then
            CHANGES=$(git log "$PREV_TAG"..HEAD --pretty=format:"- %s" --no-merges)
          else
            CHANGES=$(git log --pretty=format:"- %s" --no-merges)
          fi
          echo "changes<<EOF" >> "$GITHUB_OUTPUT"
          echo "$CHANGES" >> "$GITHUB_OUTPUT"
          echo "EOF" >> "$GITHUB_OUTPUT"

      - uses: softprops/action-gh-release@<sha>
        with:
          body: ${{ steps.changelog.outputs.changes }}
          files: dist/*
          generate_release_notes: true
```

## Deploy with environment protection

Uses GitHub Environments for staged deployment with approval gates.

```yaml
name: Deploy

on:
  push:
    branches: [main]

permissions:
  contents: read
  id-token: write    # For OIDC

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - run: npm ci && npm test

  deploy-staging:
    needs: test
    runs-on: ubuntu-latest
    environment: staging
    concurrency:
      group: deploy-staging
      cancel-in-progress: false
    steps:
      - uses: actions/checkout@<sha>
      - uses: aws-actions/configure-aws-credentials@<sha>
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1
      - run: ./scripts/deploy.sh staging

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production     # Requires manual approval
    concurrency:
      group: deploy-production
      cancel-in-progress: false
    steps:
      - uses: actions/checkout@<sha>
      - uses: aws-actions/configure-aws-credentials@<sha>
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1
      - run: ./scripts/deploy.sh production
```

## Monorepo path filtering

Only run CI for the parts of the repo that changed.

```yaml
name: Monorepo CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      frontend: ${{ steps.filter.outputs.frontend }}
      backend: ${{ steps.filter.outputs.backend }}
    steps:
      - uses: actions/checkout@<sha>
      - uses: dorny/paths-filter@<sha>
        id: filter
        with:
          filters: |
            frontend:
              - 'frontend/**'
            backend:
              - 'backend/**'

  frontend-ci:
    needs: changes
    if: needs.changes.outputs.frontend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - run: cd frontend && npm ci && npm test

  backend-ci:
    needs: changes
    if: needs.changes.outputs.backend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - run: cd backend && go test ./...
```

## Reusable CI workflow

A parameterized workflow that teams can call from their repos.

**Shared workflow** (`org/shared-workflows/.github/workflows/node-ci.yml`):
```yaml
name: Node CI (Reusable)

on:
  workflow_call:
    inputs:
      node-version:
        type: string
        default: '20'
      working-directory:
        type: string
        default: '.'
      run-e2e:
        type: boolean
        default: false
    secrets:
      NPM_TOKEN:
        required: false

jobs:
  ci:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ${{ inputs.working-directory }}
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-node@<sha>
        with:
          node-version: ${{ inputs.node-version }}
          cache: 'npm'
          cache-dependency-path: '${{ inputs.working-directory }}/package-lock.json'
      - run: npm ci
      - run: npm run lint
      - run: npm test
      - if: inputs.run-e2e
        run: npm run test:e2e
```

**Caller**:
```yaml
jobs:
  ci:
    uses: org/shared-workflows/.github/workflows/node-ci.yml@main
    with:
      node-version: '22'
      run-e2e: true
    secrets: inherit
```

## Scheduled dependency audit

```yaml
name: Dependency Audit

on:
  schedule:
    - cron: '0 9 * * 1'    # Every Monday at 9 AM UTC
  workflow_dispatch:         # Allow manual trigger

permissions:
  contents: read
  issues: write

jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-node@<sha>
        with:
          node-version: 20
      - name: Run audit
        id: audit
        continue-on-error: true
        run: |
          npm audit --json > audit-report.json 2>&1
          echo "exit_code=$?" >> "$GITHUB_OUTPUT"
      - name: Create issue on failure
        if: steps.audit.outputs.exit_code != '0'
        uses: actions/github-script@<sha>
        with:
          script: |
            const fs = require('fs');
            const report = fs.readFileSync('audit-report.json', 'utf8');
            await github.rest.issues.create({
              owner: context.repo.owner,
              repo: context.repo.repo,
              title: '🔒 npm audit found vulnerabilities',
              body: `Weekly dependency audit found issues.\n\n\`\`\`json\n${report.slice(0, 3000)}\n\`\`\``,
              labels: ['security', 'dependencies']
            });
```

## PR labeler and auto-assign

```yaml
name: PR Automation

on:
  pull_request:
    types: [opened, synchronize]

permissions:
  contents: read
  pull-requests: write

jobs:
  label:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/labeler@<sha>
        with:
          repo-token: ${{ secrets.GITHUB_TOKEN }}

  auto-assign:
    runs-on: ubuntu-latest
    if: github.event.action == 'opened'
    steps:
      - uses: actions/github-script@<sha>
        with:
          script: |
            await github.rest.issues.addAssignees({
              owner: context.repo.owner,
              repo: context.repo.repo,
              issue_number: context.issue.number,
              assignees: [context.payload.pull_request.user.login]
            });
```

## Composite action example

A reusable action that bundles multiple steps (lives in its own repo or directory).

**`action.yml`**:
```yaml
name: Setup and Test
description: Install dependencies and run tests with caching

inputs:
  node-version:
    description: Node.js version
    required: false
    default: '20'

runs:
  using: composite
  steps:
    - uses: actions/setup-node@<sha>
      with:
        node-version: ${{ inputs.node-version }}
        cache: 'npm'
    - run: npm ci
      shell: bash
    - run: npm test
      shell: bash
```

**Usage**:
```yaml
steps:
  - uses: actions/checkout@<sha>
  - uses: ./.github/actions/setup-and-test
    with:
      node-version: '22'
```
