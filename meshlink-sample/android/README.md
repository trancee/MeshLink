# Proof Android Integration

This scaffold hosts the Android proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- Android proof app wiring against the shared `:meshlink` module
- runtime Bluetooth permission handling for Android BLE proof runs
- process-scoped MeshLink proof runtime so OEM pairing flows do not tear down the test session
- start/stop controls, peer/discovery visibility, diagnostic log display, and a send-hello action in `MainActivity`
- deterministic initiator-side auto-send retries for two-device first-message validation

## Current Validation

The current proof app has been exercised on two attached Android phones:

- OPPO `CPH2689` (Android 16 / API 36)
- Samsung `SM-G970U1` (Android 12 / API 31)

Observed proof point:

- L2CAP advertisement PSM negotiation succeeds
- Noise XX hop session establishment succeeds
- provider-backed encrypted direct-message delivery succeeds from OPPO to Samsung
- Samsung receives and logs the decrypted `hello mesh` payload

## Next Steps

Later implementation tasks should add iOS parity for the proof flow, refine
GATT fallback coverage, and add automated benchmark instrumentation.
