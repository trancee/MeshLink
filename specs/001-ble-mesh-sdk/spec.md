# Feature Specification: MeshLink Offline BLE Mesh SDK

**Feature Branch**: `001-ble-mesh-sdk`  
**Created**: 2026-05-10  
**Status**: Draft  
**Input**: User description: "MeshLink is a library-first SDK for encrypted, serverless, offline-capable messaging over Bluetooth Low Energy (BLE) mesh networks — no internet, no servers, no user accounts. Core capabilities: multi-hop mesh routing, two-layer encryption, large message transfer, power-aware operation, and cross-platform Android/iOS support."

## Clarifications

### Session 2026-05-10

- Q: What first-contact peer trust model should MeshLink use? → A: TOFU (trust on first use).
- Q: What should happen when no valid route exists for a message? → A: Retry until the configured local delivery deadline expires. While no route is available, the SDK schedules retries with bounded, jittered exponential backoff and retries immediately when topology updates reveal a valid route.
- Q: What should happen when a payload exceeds the v1 transfer limit? → A: Reject the payload before transfer starts and return an explicit size-limit error.
- Q: Should pending retries survive an app or SDK restart? → A: No; pending retries are in-memory only and do not survive restart.
- Q: How should MeshLink handle an untrusted or identity-changed peer? → A: Reject the peer and emit an explicit trust-failure diagnostic.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Exchange offline encrypted messages (Priority: P1)

As a mobile app developer, I want to embed MeshLink in my app so nearby devices
can exchange encrypted messages without internet access, servers, or user
accounts.

**Why this priority**: This proves the core MeshLink promise: secure,
serverless, offline messaging between mobile devices.

**Independent Test**: Can be fully tested by running the provided proof
integrations on two supported devices with internet disabled, starting the SDK
on both devices, and sending addressed messages in both directions without any
backend dependency.

**Acceptance Scenarios**:

1. **Given** two supported devices are within BLE range, offline, and have not
   previously seen each other, **When** one device starts MeshLink and
   initiates first contact, **Then** MeshLink accepts the peer on first use,
   persists that peer identity locally, and secure messaging can begin without
   any server or account step.
2. **Given** both devices are offline and already trusted from a previous
   contact, **When** one device restarts the SDK and rejoins, **Then** secure
   messaging can resume using the persisted trust record without creating a new
   user account or attaching to an internet service.

---

### User Story 2 - Relay large messages across multiple hops (Priority: P2)

As a mobile app developer, I want MeshLink to deliver large addressed messages
through intermediate devices so users can communicate beyond direct radio range
without my app managing routes or retries manually.

**Why this priority**: Multi-hop relay and reliable large-message transfer are
what turn a direct BLE link into a practical mesh messaging SDK.

**Independent Test**: Can be tested with a three-device topology where sender
and recipient are not direct neighbors, then sending both short and large
payloads while introducing packet loss to confirm relay, retry, and reassembly.

**Acceptance Scenarios**:

1. **Given** device A can reach device B, device B can reach device C, and
   device A cannot directly reach device C, **When** device A sends a message
   to device C, **Then** MeshLink delivers it through the mesh without the host
   application selecting the intermediate route.
2. **Given** a payload is larger than a single BLE frame and some chunks are
   lost in transit, **When** the sender continues transfer, **Then** the SDK
   retransmits only the missing chunks needed for completion and the recipient
   reconstructs the original payload intact.
3. **Given** a large transfer is already in progress and the current route
   disappears, **When** an alternate valid route appears before the configured
   delivery deadline expires, **Then** MeshLink resumes the transfer from the
   missing chunk set instead of restarting the transfer from byte zero.

---

### User Story 3 - Adapt to battery state while preserving predictable SDK behavior (Priority: P3)

As a mobile app developer, I want MeshLink to adjust radio behavior when device
battery conditions worsen while keeping SDK behavior predictable across Android
and iOS so my app remains reliable on constrained mobile devices.

**Why this priority**: Power-aware behavior and cross-platform consistency are
required for real-world adoption, especially for background or long-lived mesh
participation.

**Independent Test**: Can be tested by simulating battery-state changes and the
same lifecycle workflow on Android and iOS, then verifying reduced-power
operation, continued messaging, and consistent developer-visible state and
error behavior.

**Acceptance Scenarios**:

1. **Given** a device is actively participating in the mesh, **When** the host
   device enters a lower battery tier, **Then** MeshLink reduces radio activity
   according to the selected power policy and continues best-effort message
   delivery within the documented low-power limits.
2. **Given** the same integration workflow is exercised on Android and iOS,
   **When** the app initializes, starts, pauses, resumes, and stops MeshLink,
   **Then** both platforms expose the same lifecycle states, sealed exception
   categories, and 26-code diagnostic meanings to the host application.

### Edge Cases

- If a destination peer is temporarily unreachable and no valid route exists,
  the SDK keeps delivery state in memory until the configured local delivery
  deadline expires, schedules attempts with bounded, jittered exponential
  backoff while no route exists, retries immediately when topology updates
  reveal a valid route, and returns an explicit unreachable outcome if no route
  appears before expiry.
- If a route changes while a large transfer is already in progress, the sender
  keeps the active transfer session and missing-chunk scoreboard, rebinds the
  session to the best newly valid next hop, and resumes from the missing chunk
  set if a valid route appears before the configured delivery deadline expires.
- If chunks arrive out of order or arrive twice, the receiver buffers unseen
  chunks by chunk index, discards duplicates, and acknowledges only the missing
  ranges still required for completion. Partial acknowledgements MUST NOT cause
  already accepted chunks to be retransmitted.
- If battery state changes in the middle of a transfer, the active transfer
  continues best-effort under the current delivery deadline while subsequent
  scan, advertise, and connection tuning follow the new power policy. Any
  resulting delivery failure MUST surface as an explicit timeout or unreachable
  outcome rather than silent cancellation.
- If the app or SDK restarts, pending in-memory retries are lost, stale route
  or session state is rebuilt on rejoin, and the host application must
  resubmit any message that still needs delivery.
- If an untrusted or identity-changed peer attempts to participate in end-to-end
  delivery, the SDK rejects that peer and emits an explicit trust-failure
  diagnostic.
- If the host application asks to send a payload larger than the supported
  transfer limit for the current release, the SDK rejects it before transfer
  begins and returns an explicit size-limit error.
- Android and iOS devices MUST interoperate in the same mesh topology using the
  same wire format, routing semantics, delivery outcomes, lifecycle meanings,
  error categories, and diagnostic semantics.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST initialize and run entirely on supported mobile
  devices without requiring internet connectivity, backend services, or user
  account creation.
- **FR-002**: The system MUST establish peer trust using trust on first use
  (TOFU) by accepting a previously unseen peer identity on first successful
  contact and persisting that identity locally for subsequent verification.
- **FR-003**: The system MUST treat an unexpected identity change for a
  previously trusted peer as untrusted until the stored trust record is reset
  or revoked, reject participation from that peer, and emit an explicit
  trust-failure diagnostic.
- **FR-004**: The system MUST allow trusted peers to exchange addressed
  end-to-end encrypted messages whose plaintext is accessible only to the
  origin and final destination peers.
- **FR-005**: The system MUST apply hop-by-hop protection on every adjacent mesh
  link independently of end-to-end protection so relay nodes can forward
  traffic without access to end-to-end plaintext.
- **FR-006**: The system MUST maintain proactive route knowledge and choose
  multi-hop delivery paths without requiring the host application to manage
  intermediary nodes manually.
- **FR-007**: The system MUST continue delivery attempts when topology changes
  occur by updating routes and retrying over a valid path when one exists.
  When no valid route exists, the system MUST keep the delivery pending until
  the configured local delivery deadline expires, MUST schedule retries using
  bounded, jittered exponential backoff while no route is available, MUST
  attempt delivery immediately when topology updates reveal a valid path before
  expiry, and MUST fail with an explicit unreachable outcome if no route
  appears before the deadline.
- **FR-008**: Pending delivery retries MUST remain in-memory only until the
  configured local delivery deadline expires and MUST NOT survive an app or SDK
  restart.
- **FR-009**: The system MUST support payloads larger than a single BLE frame
  through chunking, selective acknowledgement, retransmission, reassembly, and
  flow control. Payloads larger than the supported release limit MUST be
  rejected before transfer starts with an explicit size-limit error.
- **FR-010**: The system MUST detect missing, duplicated, or out-of-order chunks
  without corrupting the reconstructed payload.
- **FR-011**: The system MUST surface delivery outcomes and diagnostics for at
  least success, in-progress transfer, retrying, unreachable peer,
  trust failure, timeout, and unrecoverable transfer failure.
- **FR-012**: Users MUST be able to start, pause, resume, and stop mesh
  participation deterministically from the host application.
- **FR-013**: The system MUST expose power-aware operating behavior that reduces
  radio activity when battery conditions worsen and MUST make the current power
  mode observable to the host application.
- **FR-014**: The system MUST expose equivalent public capabilities,
  configuration concepts, lifecycle states, one shared 26-code diagnostic
  catalog with identical severity tiers and payload shapes, and one sealed
  commonMain exception hierarchy with matching categories on Android and iOS.
- **FR-015**: The system MUST function entirely offline after installation and
  required operating-system permissions are granted.
- **FR-016**: The system MUST preserve backward compatibility for deployed wire
  formats, or explicitly version and document incompatible changes.
- **FR-017**: The system MUST provide integration documentation and quickstart
  guidance sufficient for a mobile app team to complete first-message
  verification.
- **FR-018**: The system MUST keep shared messaging, routing, transfer, and
  security behavior in shared cross-platform logic while isolating
  platform-specific radio glue behind platform abstractions.

### Key Entities *(include if feature involves data)*

- **Peer Identity**: A stable device-scoped identity the SDK uses to establish
  TOFU trust, detect unexpected identity changes, and address destinations.
- **Mesh Node**: A participating device that can originate, relay, and receive
  traffic while advertising current reachability.
- **Secure Session**: The active security context for hop-by-hop and end-to-end
  protected communication.
- **Route**: The currently known path metadata used to reach a destination peer
  across one or more hops.
- **Message Transfer**: A tracked delivery operation containing payload,
  chunking state, acknowledgements, retries, and final status.
- **Power Profile**: The current radio-behavior tier that determines how
  aggressively the device scans, advertises, and maintains connectivity.
- **Diagnostic Event**: A developer-visible signal describing lifecycle,
  delivery, routing, security, or power-related outcomes.

## Constitutional Alignment *(mandatory)*

- **Code Quality & API Surface**: Applies. Public SDK surfaces must remain
  explicitly defined, Binary Compatibility Validator tracked, exactly versioned
  in dependencies, free of merged `TODO` markers, and accompanied by version
  rationale whenever `.api` output changes.
- **Testing & Benchmarking**: Applies. Mesh routing, transfer, lifecycle, and
  security logic require 100% line and branch coverage, strong assertion
  diagnostics, relevant Wycheproof validation for cryptographic primitives,
  multi-node integration coverage through the canonical virtual mesh harness,
  and benchmark evidence for touched encryption, routing, or wire-codec paths.
- **Cross-Platform Consistency**: Applies. Android and iOS must expose the same
  developer-visible SDK surface, configuration concepts, lifecycle states,
  diagnostic meanings, error categories, and documentation coverage for the same
  workflows.
- **Performance & Technical Constraints**: Applies. The SDK must remain
  offline-only, honor the project's minimum mobile platform support, keep shared
  behavior in common cross-platform logic, use the project's approved crypto
  provider abstraction, preserve deployed FlatBuffers compatibility, stay within
  the runtime dependency budget, and meet the project's latency, throughput,
  memory, battery, convergence, and codec-performance budgets where affected.

## Out of Scope *(mandatory)*

- Internet relay, cloud sync, push-notification wakeups, user-account
  management, or any server-required control plane.
- Web, desktop, or non-mobile platform SDKs in this feature scope.
- End-user UI components such as chat screens, contact lists, or onboarding
  flows owned by an application.
- Application-level social features such as groups, channels, moderation,
  presence, or contact discovery beyond addressed peer messaging.
- Media streaming, voice/video calling, or background synchronization outside
  mesh message delivery.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A mobile app team can use the runnable proof integrations in
  `samples/proof-android` and `samples/proof-ios` as reference
  implementations and complete a first encrypted offline message exchange
  between two devices in 30 minutes or less using the published quickstart.
- **SC-002**: In a three-device topology with no internet and no direct path
  between sender and recipient, addressed messages are delivered through the
  mesh without manual route selection, and route recovery after a topology
  change completes within 3 seconds.
- **SC-003**: A 64 KB payload can be delivered intact across the mesh despite
  partial chunk loss, without restarting the transfer from byte zero.
- **SC-004**: On supported reference Android and iOS hardware, a 1-hop,
  256-byte end-to-end message completes within 50 ms at p95 after connection
  establishment, and single-hop transfer sustains at least 80 KB/s on Android
  and 60 KB/s on iOS.
- **SC-005**: With up to 8 connected peers and an active routing table,
  steady-state heap allocation remains at or below 8 MB.
- **SC-006**: In LOW power mode, scan duty cycle remains at or below 5% while
  basic message delivery remains functional within the documented reduced-power
  policy.
- **SC-007**: Android and iOS reference integrations expose the same
  developer-visible lifecycle states, error categories, and diagnostic event
  meanings for equivalent workflows.

## Assumptions

- The host application owns user-facing identity presentation, trust reset or
  revocation UX, and recipient selection; MeshLink uses TOFU for first-contact
  trust and does not introduce its own account system.
- The initial release scope focuses on addressed peer-to-peer messaging rather
  than higher-level group or channel semantics.
- The maximum supported payload size for the initial release is 64 KB per
  addressed transfer; larger payloads are rejected before transfer begins.
- The SDK does not persist pending retry queues across app or SDK restarts; the
  host application is responsible for resubmitting any message that still needs
  delivery after restart.
- Supported platforms for this feature are Android API 29 or newer and iOS 15
  or newer.
- Devices grant required BLE and background-execution permissions supported by
  the operating system.
- The SDK is expected to operate with zero internet connectivity for all core
  messaging behavior after installation.
