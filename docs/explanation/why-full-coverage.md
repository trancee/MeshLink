# Why 100% Coverage for a Crypto Protocol

## The decision

MeshLink enforces 100% line and branch coverage via Kover on every commit. Zero `@CoverageIgnore` annotations are permitted in the codebase.

## This isn't about a number

In most software, 100% coverage is either impossible or a vanity metric. You write tests to hit the number, not to prove correctness. The tests become brittle, the code becomes contorted, and everyone hates it.

MeshLink is different because it's a cryptographic protocol library. The argument isn't "coverage is good" — it's "uncovered code in a protocol implementation is a specific, quantifiable risk."

## What uncovered code means here

In a crypto protocol, every branch has semantic meaning:

- An uncovered `if` in replay protection might mean a replay attack works
- An uncovered branch in Noise XX might mean a handshake state is reachable that produces wrong keys
- An uncovered path in routing might mean a loop can form under specific topology changes
- An uncovered case in the wire codec might mean a malformed message causes undefined behavior

These aren't hypothetical. Protocol bugs live in the branches you didn't test.

## How we make it practical

The 100% rule is sustainable because of deliberate code patterns:

### 1. No `require()` with string interpolation

```kotlin
// BAD — creates uncoverable Kover branch (string lambda generates bytecode branch)
require(value > 0) { "Value must be positive, got $value" }

// GOOD — explicit, all branches testable
if (value <= 0) throw IllegalArgumentException("Value must be positive, got $value")
```

### 2. No `while(isActive)` in coroutines

```kotlin
// BAD — the `false` branch of isActive is unreachable under Kover
while (isActive) { delay(1000) }

// GOOD — compiles to unconditional GOTO, no false branch
while (true) { delay(1000) }
```

### 3. Custom equals/hashCode for ByteArray data classes

Every `data class` with a `ByteArray` field overrides `equals()` and `hashCode()` using `contentEquals()`. Tests exercise each field-difference position to cover all branches.

### 4. Explicit null checks over safe-call chains

```kotlin
// BAD — generates dead null-check branch when map.remove() returns non-null type
map.remove(key)?.field

// GOOD — all branches are reachable
val entry = map.remove(key)
if (entry != null) { entry.field }
```

### 5. Integration tests for wiring paths

Methods that only execute when MeshEngine is fully composed (handshake callbacks, power tier transitions) are covered by `MeshTestHarness` integration tests that run real Noise XX handshakes over VirtualMeshTransport.

## What it catches

Real bugs found by chasing coverage:
- RouteCoordinator installing routes with all-zero X25519 key (invisible in unit tests, crashes at distant nodes)
- Missing handshake subscription in MeshEngine (only activates on incoming frame from real transport)
- PowerModeEngine timer leak on rapid start/stop cycling
- DedupSet expiry race under concurrent access

## The cost

- ~10% more test code than a "pragmatic 90%" approach
- Occasional need to restructure code to eliminate dead branches
- Higher bar for contributors

## The return

- Zero protocol-level bugs in production paths (all reachable states are exercised)
- Refactoring confidence (move code freely, coverage regression = immediate signal)
- The `@CoverageIgnore` removal in M005 found 3 genuinely untested behaviors
