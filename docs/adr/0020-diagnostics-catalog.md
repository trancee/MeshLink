# 0020: Diagnostics catalog

Status: accepted (2026-11-08; ticket "What is the diagnostics code catalog?"). Version: 5 (amended 2026-11-26, ticket 54a; 2026-11-26, ticket 54b; 2026-11-26, ticket 54c; 2026-11-27, ticket 54d).

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
   - A repeat of the same code and reference within 10 s is coalesced into one event with a count (amended 2026-11-26).
   - Each collector's buffer holds 256 events and drops the oldest first, then emits `ML7001` (amended 2026-11-26).
5. **Snapshot.** `diagnostics.snapshot()` returns the current redacted state (amended 2026-11-27):
   - configuration, profile and density class;
   - each link, with its bearer, PHY, MTU and role;
   - queue and held-message counts, and the size of the certificate cache;
   - cumulative counters: every reported event by code, and frames sent, frames relayed and handshakes succeeded.

   It has no time series.
6. **Field tooling.**
   - `diagnostics.export()` returns a JSON report ([`docs/reference/diagnostics-export.md`](../reference/diagnostics-export.md)): the snapshot, the last 500 events, the MeshLink and protocol versions, the platform and the OS version. MeshLink never uploads it; the app decides what happens to it.
   - Opt-in `diagnostics.platformLog` writes events to Logcat or `os_log`. In `os_log`, fields other than codes and numbers are marked private. The line format is fixed by the 2026-11-26 platform-codes amendment.
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
   | ~~ML2003~~ | retired: `ScanRestarted` (amended 2026-11-26) | |
   | ML2004 ★ | `BackgroundDiscoveryDegraded`; with the lifecycle work (amended 2026-11-26) | W |
   | ML2005 | `AdvertisingFailed(platformCode)` | W |
   | ML2006 ★ | `StaleAddressMarked` | D |
   | ML2007 ★ | `DensityClassChanged(from, to)` | I |
   | ML2008 | `GattServiceFailed(platformCode)` (amended 2026-11-26) | W |
   | ML3001 | `LinkOpened(peer, role, mtu)`; no `phy` (amended 2026-11-26) | I |
   | ML3002 | `LinkClosed(peer, reason, byPeer, platformCode)` (amended 2026-11-26) | I |
   | ML3003 | `ConnectFailed(link, reason, attempt, platformCode)` (amended 2026-11-26) | W |
   | ML3004 | `LinkCapReached(cap)` | D |
   | ML3005 | `BearerFallback(peer, reason)`, L2CAP CoC to GATT | I |
   | ML3006 | `GattOperationRetried(platformCode)` | D |
   | ML3007 ★ | `UnknownLinkFrameDropped(type)` | D |
   | ML3008 | `ProtocolVersionRejected(theirVersion)` | W |
   | ML3009 | `BusyReply(direction, reason)` (amended 2026-11-26) | D |
   | ML3010 | `BearerSwitched(peer)`, GATT to L2CAP CoC (amended 2026-11-26) | D |
   | ML4001 | `HandshakeFailed(link, reason)`. Reasons: `Timeout`, `Decrypt`, `Certificate`, `Protocol` (amended 2026-11-26) | W |
   | ML4002 ★ | `HandshakeLimitDropped(limit)` | W |
   | ~~ML4003~~ | retired: `IdentityKeyChanged` (amended 2026-11-26) | |
   | ML4004 | `BroadcastSignatureInvalid` | D |
   | ~~ML4005~~ | retired: `ReplayDropped` (amended 2026-11-26) | |
   | ML5001 | `RouteFound(peer, hops)` | D |
   | ML5002 | `RouteLost(peer)` | D |
   | ML5003 | `UnknownRoutedFrameRelayed(type)` | D |
   | ML5004 ★ | `ExpiryFarFromLocalTime(skewSeconds)` | W |
   | ML6001 | `HeldMessageEvicted(reason)` | I |
   | ML6002 | `CertificateLookup(peer, attempts)` (amended 2026-11-26) | D |
   | ML6003 | `CertificateLookupUnanswered(peer, attempts)` | W |
   | ML6004 | `InvalidCertificateReply` | D |
   | ML7001 ★ | `DiagnosticsDropped(dropped)` (amended 2026-11-26) | W |
   | ML7002 ★ | `ReadTrackingEvicted(messageId)` | I |
   | ML7003 | `InboxFull(bound)` (amended 2026-11-26) | W |
   | ML7004 | `ReassemblyBudgetExhausted` | D |
   | ML8001 | `ScannerRegistrationFailed`, the leaked-registration symptom on Android | W |
   | ML8002 | `AppSuspendedByOs`; with the lifecycle work (amended 2026-11-26) | I |
   | ML8003 | `DozeActive`; with the lifecycle work (amended 2026-11-26) | I |

   The list may change until 0.1.0.
8. **Troubleshooting.**
   - `docs/how-to/troubleshoot-peers-not-found.md`, `troubleshoot-messages-not-delivered.md` and `troubleshoot-high-battery-use.md` link to these codes.
   - The pages are written together with the code, and their entries are generated per ADR 0006.

## Amendments

2026-11-26, first implementation (ticket 54a; user decisions on the ticket's four questions):

- **Context:** the first eleven codes are implemented, and four fields of decision 7 did not fit the code as written.
- **Decision:**
  - **Reasons are public enums** nested in their event, such as `HandshakeFailed.Reason`, so apps branch on them exhaustively and a new reason is visible in the API dump.
  - **`HandshakeFailed` reasons** are `Timeout`, `Decrypt`, `Certificate` and `Protocol`: the failures a handshake can tell apart. Another profile, mesh ID or mesh key fails to decrypt, so all three are `Decrypt`, and the KDoc says so.
  - **`ConnectFailed` keeps `reason`**, MeshLink's own classification of the platform failure, until the platform-codes ticket adds `platformCode` beside it.
  - **`DiagnosticsDropped(dropped)`:** every event already has `count` for coalesced repeats, so the number of lost events is `dropped`.
  - **Flood control, made precise:**
    - The first event of a code and reference is emitted at once with count 1. Repeats within the next 10 s are counted, and at the end of the window the latest repeat is emitted with the number of repeats, if there were any.
    - An event about neither a peer nor a link coalesces by its code alone.
    - `diagnostics` is a hot flow without replay. Each collector has its own 256-event buffer, so a slow collector loses only its own events, and the flow completes on `close()`.
- **Status:** decisions 4 and 7 are updated to match; the generated index is [`docs/reference/diagnostics.md`](../reference/diagnostics.md).

2026-11-26, common codes (ticket 54b; user decisions on the ticket's six questions):

- **Context:** the codes the common code can emit are implemented. Three codes of decision 7 describe nothing the protocol can observe, and five field lists did not fit the code as written.
- **Decision:**
  - **Three codes are retired** and never reused (decision 1):
    - `ML2003 ScanRestarted`: every scan window starts a fresh scan, so `ML2001 ScanWindow` already marks each restart. ADR 0010's promise of scan-restart diagnostics is met by `ML2001`.
    - `ML4003 IdentityKeyChanged`: a NodeId derives from its identity key, so a peer cannot change its key and keep its NodeId.
    - `ML4005 ReplayDropped`: the protocol has no replay window. A replayed link frame fails its AEAD check and closes the link as `ML3002` with reason `ProtocolError`.
  - **`LinkOpened(peer, role, mtu)`:** each link opens on GATT, so `bearer` is gone, and the new Debug code `ML3010 BearerSwitched(peer)` reports a successful switch to L2CAP CoC. `phy` arrives with the platform codes.
  - **`LinkClosed(peer, reason, byPeer)`:** only for links that opened. `reason` is a close reason of the spec, or `ConnectionLost` when the bearer dropped without one. `platformCode` arrives with the platform codes.
  - **`BusyReply(direction, reason)`:** every `BUSY` sent or received, with reason `Slots` or `Budget`. `ML7004` is emitted only when this node refuses for its own large-frame budget.
  - **`CertificateLookup(peer, attempts)`:** emitted when a lookup succeeds, with the requests it sent. It is emitted only on success, and the requester cannot tell whether the target or a node's cache answered, nor how many hops the reply crossed, so `answered`, `fromCache` and `hops` are gone. `ML6003` reports the requests sent by the deadline.
  - **`HeldMessageEvicted(reason)`:** only capacity evictions, with reason `LinkShare` or `ClosestToExpiry`. An expired frame is not reported.
  - **`InboxFull(bound)`:** the bound the message would have crossed: `Count`, `Bytes` or `Sender`.
  - **Booleans and negative numbers are allowed field values** (decision 3), for `byPeer` and `skewSeconds`.
- **Status:** decision 7 is updated to match.

2026-11-26, platform codes and the platform log (ticket 54c; user decisions on the ticket's eight questions):

- **Context:** the Android and iOS radio adapters report their failures. Decision 7 left `platformCode` and `phy` open, and decision 6 named the platform log without fixing its format.
- **Decision:**
  - **`platformCode`** is Android's raw status or error code, or on iOS the code of an `NSError` in `CBErrorDomain`; any other iOS error domain gives `null`. It is `null` whenever the platform gave none.
  - **Scan and service failures are separate:** `ML2002 ScanFailed` covers scanning only, and the new `ML2008 GattServiceFailed(platformCode)`, severity Warning, covers a GATT service the platform failed to publish. Both stop discovery.
  - **`ML8001`** is emitted beside `ML2002` when Android refuses a scan with error 2, `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`.
  - **`ML3006 GattOperationRetried`** is emitted once per Android GATT request that found the stack busy, with `platformCode` 201 (`ERROR_GATT_WRITE_REQUEST_BUSY`) on Android 13 and later, and `null` before, where the busy result is a `false` return. iOS queues requests itself and never emits it.
  - **`LinkClosed.platformCode`** is set only for reason `ConnectionLost`: it is the status of the first platform disconnect, such as 8 for a supervision timeout. A close MeshLink starts records none. **`ConnectFailed.platformCode`** is the connect status, such as 133 or 147, or the `CBError` of `didFailToConnect`.
  - **No `phy` on `ML3001`:** iOS does not expose the PHY, and Android reports it only through `readPhy` or a later `onPhyUpdate`, after the link opens. The per-link PHY belongs in the diagnostics snapshot.
  - **The lifecycle codes `ML2004`, `ML8002` and `ML8003` move** to the lifecycle work, which watches app suspension and Doze.
  - **Platform log line:** `ML3002 LinkClosed count=1 platformCode=8 peer=ab12cd34 reason=ConnectionLost byPeer=false`: the code, the event name, the coalesced count and every number field, then every other field. Android writes it to Logcat with tag `MeshLink` at the priority of the severity. iOS writes it to `os_log` subsystem `ch.trancee.meshlink`, category `diagnostics`, with the first part `%{public}s` and the rest `%{private}s`; Debug, Info, Warning and Error map to `debug`, `info`, `default` and `error`. Every emitted event is written, whether or not the app collects; `ML7001`, which counts one collector's drops, is not.
  - **The iOS writer is a C wrapper in a cinterop definition**, because `os_log` is a macro over a variadic call and needs a constant format string; Kotlin/Native can call neither. Formatting stays in common code.
- **Status:** decisions 6 and 7 are updated to match.

2026-11-27, snapshot and export (ticket 54d; user decisions on the ticket's eight questions and an amended third):

- **Context:** the snapshot and the export are implemented. Detekt's limit of 11 functions per class, which the project does not suppress, left no room on `MeshLink` for two more functions, and its limit of 6 constructor parameters left none on a flat snapshot.
- **Decision:**
  - **One diagnostics type:** `MeshLink.diagnostics` is a `MeshDiagnostics` with `events`, the flow of ADR 0007 decision 12, `suspend fun snapshot()` and `suspend fun export()`, each callable in any state, also after `close()`. It is not named `Diagnostics`, which is the type of the config value `diagnostics`.
  - **Grouped snapshot:** `DiagnosticsSnapshot` holds `configuration`, `profile`, `densityClass` (`null` while the node isn't running), `links`, `queues` (`QueueCounts`: `outgoing`, `held`, `inbox`, `certificates`) and `counters` (`DiagnosticCounters`).
  - **Counters (decision 5):** every reported event is counted by code, before the Debug filter and coalescing, so a failure by reason, a retry or a scan window is the count of its code. Three counts no event carries are explicit: `framesSent`, `framesRelayed` and `handshakesSucceeded`. All reset on each `start()`.
  - **PHY:** each `LinkSnapshot` has `phy`: `Le1M`, `Le2M` or `LeCoded`. Android reads it with `readPhy` when the link opens and follows `onPhyUpdate`; iOS doesn't expose it, so it is `null`.
  - **Redaction (decision 3):** the configuration shows `meshId = <redacted>`, and a mesh key was already `<redacted>`. Only links past their hellos are listed, each by `PeerRef`.
  - **Export (decision 6):** a JSON document with `"format": 1`, the MeshLink, protocol, platform and OS versions, the snapshot, and the last 500 events emitted after the Debug filter and coalescing, each with its wall-clock time `at`. The history spans starts and is cleared only when the node is created; `ML7001` is not in it. A hand-written encoder in common code writes it, with no new dependency (E3). `format` grows only when a field changes meaning or goes. `DiagnosticsExportReferenceTest` generates the reference from the KDoc and the encoder.
- **Status:** decisions 5 and 6 and the consequences are updated to match.

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

- **[ADR 0007](0007-public-api-shape.md):** gains `diagnostics.snapshot()`, `diagnostics.export()`, `diagnostics.debug` and `diagnostics.platformLog` (amended 2026-11-27).
- **The generated diagnostic index and the troubleshooting pages** arrive with the code (ADR 0006).
- **The device-lab scripts (ADR 0017)** parse the platform log format.
