# Why pure-Kotlin FlatBuffers

## The decision

MeshLink implements a FlatBuffers-compatible binary codec in pure Kotlin. It
does not use `flatc` code generation or a commonMain FlatBuffers runtime
library.

## Why this exists

MeshLink needs one shared wire codec across JVM, Android, and iOS. The usual
FlatBuffers options did not fit that requirement cleanly:

- `flatbuffers-java` is JVM-only
- there is no official KMP FlatBuffers runtime on Maven Central
- `flatc` output assumes runtimes that do not solve the shared-code problem for
  this repository

## What MeshLink built instead

The shared codec is intentionally small:

- `ReadBuffer` handles FlatBuffers-compatible reads
- `WriteBuffer` handles FlatBuffers-compatible writes
- message-specific encode/decode logic lives in `WireCodec.kt`

The goal was not to reimplement the entire FlatBuffers ecosystem. It was to
support the subset MeshLink actually uses.

## Why the manual approach is reasonable here

The FlatBuffers binary format is stable and well specified. MeshLink only needs
a narrow feature slice:

- flat tables
- scalar fields
- byte arrays and strings
- forward-compatible field skipping

It does **not** need the full feature matrix such as unions or the broader code
generation toolchain.

## What this buys the project

- one shared codec across all supported targets
- no new runtime dependency in `:meshlink`
- exact control over the wire format
- a smaller code footprint than generated code for the subset MeshLink uses

## What proves it works

The repository keeps the codec under the same test discipline as the rest of the
protocol surface:

- full coverage expectations
- round-trip tests
- malformed-input validation
- forward-compatibility checks
- JVM-side comparison against a reference FlatBuffers implementation where that
  is useful

## Trade-off

The cost is maintenance. Schema evolution requires explicit manual updates
instead of re-running a generator.

MeshLink accepts that cost because the shared-target compatibility and runtime
control matter more than generator convenience in the current repository shape.

## When to revisit

If a well-supported KMP FlatBuffers runtime becomes available and fits the
project's dependency rules, this decision can be revisited without changing the
wire format itself.

## Related docs

- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [About the repository architecture](about-the-repository-architecture.md)
- [Why 100% coverage for a crypto protocol](why-full-coverage.md)
