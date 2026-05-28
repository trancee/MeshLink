# Why 100% coverage for a crypto protocol

## The decision

MeshLink enforces 100% line and branch coverage via Kover. No
`@CoverageIgnore` annotations are permitted in the codebase.

## Why this is not just a vanity metric

In many projects, 100% coverage becomes a number people chase instead of a real
signal. MeshLink treats it differently because it is a cryptographic protocol
library.

Here, an uncovered branch is not just "missing test polish." It can mean a
reachable protocol behavior that nobody has proven safe.

## What uncovered code means in this codebase

In MeshLink, branches often carry protocol meaning:

- replay-protection behavior
- handshake transitions
- routing decisions
- malformed-frame handling
- power-policy transitions

Those are exactly the places where subtle bugs hide.

## How MeshLink makes the rule practical

The coverage rule stays sustainable because the code avoids patterns that create
hard-to-exercise bytecode branches.

### 1. Prefer explicit checks over `require()` lambdas

```kotlin
// BAD
require(value > 0) { "Value must be positive, got $value" }

// GOOD
if (value <= 0) throw IllegalArgumentException("Value must be positive, got $value")
```

### 2. Avoid `while (isActive)` loops in long-running coroutines

```kotlin
// BAD
while (isActive) { delay(1000) }

// GOOD
while (true) { delay(1000) }
```

### 3. Override `ByteArray` equality deliberately

`ByteArray` needs `contentEquals()` and `contentHashCode()` semantics, not the
default identity-based behavior.

### 4. Prefer explicit null handling over opaque safe-call chains

Explicit branches are easier to reason about and easier to cover honestly.

### 5. Cover wiring paths with integration tests

Some important behavior only appears when the full engine is composed. That is
why MeshLink uses the virtual harness, not only small unit tests.

## What the rule buys back

The cost is real:

- more test code
- occasional code reshaping to remove dead or misleading branches
- a higher contributor bar

The return is also real:

- stronger confidence in protocol changes
- faster detection of untested behavior during refactors
- fewer places for "reachable but never exercised" bugs to hide

## Related docs

- [Contributor build, test, and verification reference](../reference/contributor-reference.md)
- [About the repository architecture](about-the-repository-architecture.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [MeshLink Constitution](../../constitution.md)
