# Proof iOS Integration

This scaffold hosts the iOS proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- direct KMP integration notes are expected in this directory
- the SwiftUI entry point is scaffolded under `ProofApp/`
- the proof app now installs an iOS `CryptoKit` bridge at startup via `ProofApp/MeshLinkCryptoBridge.swift`
- benchmark source files will live under `ProofBenchmarks/`

## Next Steps

Later implementation tasks will replace the placeholder UI with the full proof
flow, diagnostics display, and benchmark instrumentation.
