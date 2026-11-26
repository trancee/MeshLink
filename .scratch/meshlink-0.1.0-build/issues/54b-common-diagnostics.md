# Emit the common-code diagnostics

Status: done
Type: AFK
Blocked by: 54a

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). Every ADR 0020 v1 code whose site is in common code is emitted there, through the 54a hierarchy:

- discovery: ML2001 `ScanWindow`, ~~ML2003 `ScanRestarted`~~, ML2006 `StaleAddressMarked`, ML2007 `DensityClassChanged`;
- links: ML3001 `LinkOpened`, ML3002 `LinkClosed` (its `platformCode` stays absent until 54c), ML3008 `ProtocolVersionRejected`, ML3009 `BusyReply`, ML3010 `BearerSwitched` (new);
- security: ML1004 `OwnCertificateRenewed`, ~~ML4003 `IdentityKeyChanged`~~, ML4004 `BroadcastSignatureInvalid`, ~~ML4005 `ReplayDropped`~~;
- routing: ML5001 `RouteFound`, ML5002 `RouteLost`, ML5003 `UnknownRoutedFrameRelayed`, ML5004 `ExpiryFarFromLocalTime`;
- store-and-forward and certificates: ML6001 `HeldMessageEvicted`, ML6002 `CertificateLookup`, ML6003 `CertificateLookupUnanswered`, ML6004 `InvalidCertificateReply`;
- limits: ML7003 `InboxFull`, ML7004 `ReassemblyBudgetExhausted`.

A code found to need an adapter moves to 54c; a code with no site in the built protocol is removed or renamed through an ADR 0020 amendment in this PR.

Outcome (owner decisions 2026-11-26): ML2003, ML4003 and ML4005 have no site in the built protocol and are retired, never reused; ML3010 is new. ADR 0020 v3 records both, with the changed fields of ML3001, ML3002, ML3009, ML6002 and ML7003.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decisions 3 and 7.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md): no routing tags or advertisement identifiers in events.
- ADRs naming the ★ codes: 0008 (density), 0010 (stale addresses, scan restarts), 0012 (expiry skew).

## Acceptance criteria

- [x] Each listed code is emitted at its site, with a `VirtualAir` test that triggers it and checks its fields. Owner decision B: ML5003, ML5004, ML6001 and ML6004 need a hostile or overloaded mesh to trigger, so unit tests at their sites (`RouterTest`, `FloodsTest`, `HoldingTest`, `LookupsTest`) check their fields; every other code has a `VirtualAir` test.
- [x] The redaction test covers every new event type (S1).
- [x] The generated index lists every new code; `./gradlew updateReference` leaves `docs` unchanged.
- [x] Any change to the code list amends ADR 0020 in this PR.
- [x] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
```

## Docs to update

- Generated index (regenerated); `CHANGELOG.md`; ADR 0020 amendment if the list changes.

## Devices needed

None.
