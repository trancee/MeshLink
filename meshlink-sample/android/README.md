# Proof Android Integration

This scaffold hosts the Android proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- minimal Android app wiring
- dependency on the shared `:meshlink` module
- an on-device crypto and factory diagnostic surface in `MainActivity`

## Next Steps

Later implementation tasks will replace the placeholder UI with the full proof
flow, diagnostics display, and benchmark instrumentation.
