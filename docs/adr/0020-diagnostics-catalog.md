# 0020: Diagnostics catalog

Status: accepted (2026-11-08; ticket "What is the diagnostics code catalog?"). Version: 1.

## Context

- **Already fixed by [ADR 0007](0007-public-api-shape.md) decision 12:**
  - `diagnostics: Flow<DiagnosticEvent>`;
  - each event has a stable code, a severity and redacted fields;
  - a code range separate from rejection and failure reasons;
  - a bounded buffer with debug detail off by default, where an overflow produces a summary event.
- **Already fixed by [ADR 0006](0006-documentation-architecture.md):**
  - Codes are closed Kotlin hierarchies with KDoc.
  - A JVM test generates the reference and the diagnostic index (code → meaning → likely cause → fix) in Markdown and JSON.
  - Troubleshooting pages group codes by symptom.
- **Constitution:**
  - C5: names, severity and payload shape are stable and documented.
  - S1: no secrets, keys or personal data; identifiers are redacted.
- **Codes promised by earlier ADRs:**
  - the `Unsecured` warning (ADR 0007);
  - density class switches (ADR 0008);
  - background-degraded discovery, stale addresses and scan restarts (ADR 0010);
  - handshake limit drops (ADR 0002);
  - unknown link-local frames and expiries far from local time (ADR 0012);
  - evicted read tracking (ADR 0013).
- **Device lab ([lessons](../agents/lessons.md#device-lab)):** the failures seen in the field are:
  - GATT status 133/147 on first connect;
  - leaked scanner registrations ("scan failed 2");
  - Bluetooth stack asserts;
  - iOS links closing without an error;
  - OS-managed background duty (Doze, iOS suspension).

  Platform status numbers were the main clue each time.
- **Privacy ([ADR 0016](0016-routing-metadata-exposure.md)):** routing tags, advertisement identifiers and lookup salts are what observers correlate on.

## Decision

1. **Code shape.**
   - `ML` plus four digits, in ranges by area:

     | Range | Area |
     |---|---|
     | 1xxx | lifecycle and configuration |
     | 2xxx | discovery |
     | 3xxx | links and bearers |
     | 4xxx | handshakes and security |
     | 5xxx | routing |
     | 6xxx | store-and-forward and certificates |
     | 7xxx | limits and resources |
     | 8xxx | platform |

   - Each code is a subclass of `DiagnosticEvent` with typed fields.
   - A code is never reused. After 0.1.0, adding a code is a MINOR change, and a code is removed only through deprecation (ADR 0019).
2. **Severity:**
   - `Debug`: emitted only when `diagnostics.debug` is on.
   - `Info`: state changes.
   - `Warning`: degraded but working.
   - `Error`: a feature is not working.
3. **Privacy.**
   - **Peers:** a peer appears as `PeerRef`, a 4 B keyed BLAKE2b hash of its NodeId under a key drawn at random on each `start()`, so events correlate within a session but not across sessions. A link whose peer is not yet authenticated appears as `LinkRef`, a counter per session.
   - **Never in an event:** keys, payloads, routing or conversation tags, advertisement identifiers, lookup salts or certificates.
   - **Allowed:** message IDs the app already holds, and platform status numbers such as GATT 133.
4. **Flood control.**
   - A repeat of the same code and reference within 10 s is coalesced into one event with a count.
   - The buffer holds 256 events and drops the oldest first, then emits `ML7001`.
5. **Snapshot.** `diagnosticsSnapshot()` returns the current redacted state:
   - configuration, profile and density class;
   - each link, with its bearer, PHY, MTU and role;
   - queue and held-message counts, and the size of the certificate cache;
   - cumulative counters: frames sent, relayed and dropped by reason; handshakes succeeded and failed by reason; retries; scan restarts.

   It has no time series.
6. **Field tooling.**
   - `exportDiagnostics()` returns a JSON report: the snapshot, the last 500 events, the MeshLink and protocol versions, the platform and the OS version. MeshLink never uploads it; the app decides what happens to it.
   - Opt-in `diagnostics.platformLog` writes events to Logcat or `os_log`. In `os_log`, fields other than codes and numbers are marked private.
   - The device-lab scripts (ADR 0017) read these logs.
7. **v1 codes.** Severity: D Debug, I Info, W Warning. ★ marks codes promised by an earlier ADR.

   | Code | Event and fields | Severity |
   |---|---|---|
   | ML1001 | `Started(profile, preset)` | I |
   | ML1002 | `Stopped(reason)` | I |
   | ML1003 ★ | `UnsecuredProfile`, on every start | W |
   | ML1004 | `OwnCertificateRenewed` | I |
   | ML2001 | `ScanWindow(started or stopped)` | D |
   | ML2002 | `ScanFailed(platformCode)` | W |
   | ML2003 ★ | `ScanRestarted(reason)` | I |
   | ML2004 ★ | `BackgroundDiscoveryDegraded` | W |
   | ML2005 | `AdvertisingFailed(platformCode)` | W |
   | ML2006 ★ | `StaleAddressMarked` | D |
   | ML2007 ★ | `DensityClassChanged(from, to)` | I |
   | ML3001 | `LinkOpened(peer, bearer, phy, mtu, role)` | I |
   | ML3002 | `LinkClosed(peer, reason, platformCode)` | I |
   | ML3003 | `ConnectFailed(link, platformCode, attempt)` | W |
   | ML3004 | `LinkCapReached(cap)` | D |
   | ML3005 | `BearerFallback(peer, reason)`, L2CAP CoC to GATT | I |
   | ML3006 | `GattOperationRetried(platformCode)` | D |
   | ML3007 ★ | `UnknownLinkFrameDropped(type)` | D |
   | ML3008 | `ProtocolVersionRejected(theirVersion)` | W |
   | ML3009 | `BusyReply(sent or received)` | D |
   | ML4001 | `HandshakeFailed(link, reason)`. Reasons: timeout, decrypt, invalid or expired certificate, profile mismatch, mesh-key mismatch | W |
   | ML4002 ★ | `HandshakeLimitDropped(limit)` | W |
   | ML4003 | `IdentityKeyChanged(peer)` | W |
   | ML4004 | `BroadcastSignatureInvalid` | D |
   | ML4005 | `ReplayDropped` | D |
   | ML5001 | `RouteFound(peer, hops)` | D |
   | ML5002 | `RouteLost(peer)` | D |
   | ML5003 | `UnknownRoutedFrameRelayed(type)` | D |
   | ML5004 ★ | `ExpiryFarFromLocalTime(skewSeconds)` | W |
   | ML6001 | `HeldMessageEvicted(reason)` | I |
   | ML6002 | `CertificateLookup(peer, answered, fromCache, hops)` | D |
   | ML6003 | `CertificateLookupUnanswered(peer, attempts)` | W |
   | ML6004 | `InvalidCertificateReply` | D |
   | ML7001 ★ | `DiagnosticsDropped(count)` | W |
   | ML7002 ★ | `ReadTrackingEvicted(messageId)` | I |
   | ML7003 | `InboxFull` | W |
   | ML7004 | `ReassemblyBudgetExhausted` | D |
   | ML8001 | `ScannerRegistrationFailed`, the leaked-registration symptom on Android | W |
   | ML8002 | `AppSuspendedByOs` | I |
   | ML8003 | `DozeActive` | I |

   The list may change until 0.1.0.
8. **Troubleshooting.**
   - `docs/how-to/troubleshoot-peers-not-found.md`, `troubleshoot-messages-not-delivered.md` and `troubleshoot-high-battery-use.md` link to these codes.
   - The pages are written together with the code, and their entries are generated per ADR 0006.

## Alternatives

- **Free-text log lines:** neither stable nor machine-readable (C5). Rejected.
- **The NodeId or a stable hash in events:** an exported report would let anyone link a phone's sessions and contacts. Rejected in favor of a per-start key.
- **Routing tags or advertisement identifiers in events:** they are exactly what ADR 0016 limits. Rejected.
- **Platform logging on by default:** writes to shared system logs that other tools collect. Rejected; it is opt-in.
- **Time-series metrics in the snapshot:** memory and API surface for something apps can build from events. Rejected.
- **Upload or telemetry:** MeshLink sends nothing off the device. Out of scope.
- **String codes such as `LINK_CONNECT_FAILED`:** readable, but renames break consumers. Numbers with names in KDoc keep both stable.

## Risks

- **Coalescing hides timing:** 10 s coalescing loses exact event times. The counters and debug mode cover deeper investigations.
- **Platform status codes vary by OEM:** the index explains the common ones (133, 147, scan failure 2), and the others stay raw numbers.
- **Too many Debug codes:** they cost nothing while debug is off. Each must still have an index entry.
- **The list is pre-implementation:** it may change until 0.1.0 (decision 7).

## Consequences

- **[ADR 0007](0007-public-api-shape.md):** gains `diagnosticsSnapshot()`, `exportDiagnostics()`, `diagnostics.debug` and `diagnostics.platformLog`.
- **The generated diagnostic index and the troubleshooting pages** arrive with the code (ADR 0006).
- **The device-lab scripts (ADR 0017)** parse the platform log format.
