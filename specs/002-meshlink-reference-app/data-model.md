# Data model: MeshLink reference app

## Overview

The reference app adds application-level session, scenario, filtering, and
export models around the existing MeshLink SDK. It does not redefine MeshLink
protocol entities such as routes, wire frames, or trust records inside the SDK;
it presents them through a user-facing reference layer.

## Entities

### `ReferenceScenario`

Represents a named workflow or exercise the operator can run.

| Field | Type | Description | Validation |
|---|---|---|---|
| `scenarioId` | String | Stable unique identifier for the scenario | Required; unique across the app |
| `surface` | Enum | `main`, `advanced`, or `lab` | Required |
| `mode` | Enum | `live`, `solo`, or `hybrid` | Required |
| `title` | String | Operator-facing scenario name | Required; non-blank |
| `summary` | String | One-line description of the goal | Required |
| `prerequisites` | List<String> | Conditions the operator must satisfy | May be empty for solo-only flows |
| `successSignals` | List<String> | Observable outcomes proving success | Required |
| `blockedGuidance` | List<String> | Recovery hints when prerequisites fail | Required |
| `capabilityTags` | Set<String> | MeshLink capabilities demonstrated | Required; at least one tag |

**Relationships**:
- One `ReferenceScenario` can produce many `ReferenceSession` values.
- `ReferenceScenario.surface` controls whether the scenario appears in the main
  experience, advanced area, or lab.

### `ReferenceSession`

Represents one run of a scenario.

| Field | Type | Description | Validation |
|---|---|---|---|
| `sessionId` | String | Stable unique session identifier | Required; unique |
| `scenarioId` | String | Owning scenario | Must reference an existing `ReferenceScenario` |
| `authorityMode` | Enum | `live` or `solo` | Required |
| `startedAt` | Instant | Session start timestamp | Required |
| `endedAt` | Instant? | Session end timestamp | Null while active |
| `meshState` | Enum | Current visible runtime state | Required |
| `selectedPeerId` | String? | Currently selected peer for operator actions | Optional |
| `configurationSnapshot` | Map<String, String> | Operator-visible configuration values | Required |
| `lastOutcomeSummary` | String? | Most recent high-level result | Optional |
| `historyStatus` | Enum | `live`, `retained`, `exported`, `deleted` | Required |

**Relationships**:
- One `ReferenceSession` owns many `TimelineEntry` values.
- One `ReferenceSession` can produce zero or more `SessionArtifact` values.
- One ended session can appear in `RecentSessionHistory`.

### `PeerSnapshot`

Represents the app's current view of one MeshLink peer.

| Field | Type | Description | Validation |
|---|---|---|---|
| `peerId` | String | Full runtime peer handle used for actions | Required in live memory |
| `peerSuffix` | String | Redacted identifier shown in history and exports by default | Required |
| `trustState` | Enum | `unknown`, `trusted`, `changed`, `forgotten` | Required |
| `connectionState` | Enum | `connected`, `disconnected`, `lost` | Required |
| `lastSeenAt` | Instant? | Most recent observation time | Optional |
| `lastDeliveryOutcome` | String? | Most recent send/receive outcome summary | Optional |
| `capabilityNotes` | List<String> | Human-readable notes in the app context | Optional |

### `TimelineEntry`

Represents one operator-visible line item in the technical timeline.

| Field | Type | Description | Validation |
|---|---|---|---|
| `entryId` | String | Stable unique identifier within a session | Required; unique per session |
| `sessionId` | String | Owning session | Required |
| `occurredAt` | Instant | Event timestamp | Required |
| `family` | Enum | `user`, `lifecycle`, `peer`, `diagnostic`, `message`, `transfer`, `export` | Required |
| `severity` | Enum | `info`, `warning`, `error`, `success`, `debug` | Required |
| `title` | String | Short summary line | Required |
| `detail` | String | Expanded explanation | Required |
| `peerSuffix` | String? | Redacted peer reference | Optional |
| `searchText` | String | Concatenated searchable content | Required |
| `payloadPreview` | String? | Redacted preview when relevant | Optional |
| `payloadSizeBytes` | Int? | Original payload size when known | Optional |
| `fullPayload` | String? | Full payload content when explicitly captured for export | Optional |
| `fullPayloadIncluded` | Boolean | Whether a full payload copy is currently attached to the entry | Defaults to `false` |

### `SessionArtifact`

Represents an exported or retained structured session document.

| Field | Type | Description | Validation |
|---|---|---|---|
| `artifactId` | String | Stable unique artifact identifier | Required |
| `sourceSessionId` | String | Session being exported | Required |
| `createdAt` | Instant | Export creation timestamp | Required |
| `payloadPolicy` | Enum | `metadata-only`, `redacted-preview`, `full-opt-in` | Required |
| `includesFullPayload` | Boolean | Whether full payload content is present | `true` only after explicit opt-in |
| `scenarioSummary` | Map<String, String> | Exported scenario metadata | Required |
| `peerSummaries` | List<Map<String, String>> | Exported redacted peer summary | Required |
| `timelineEntries` | List<TimelineEntry> | Serialized timeline records | Required |
| `storagePath` | String | App-local path for the artifact | Required for retained/exported artifacts |

### `RecentSessionHistory`

Represents the bounded retained-history index.

| Field | Type | Description | Validation |
|---|---|---|---|
| `maxSessions` | Int | Maximum number of retained sessions | Fixed at `20` |
| `sessionIds` | List<String> | Most recent retained sessions, newest first | Length must be `<= 20` |
| `lastPrunedAt` | Instant? | Timestamp of latest history pruning | Optional |

## Validation rules

- Lab scenarios must never appear in the main guided experience.
- Solo mode must never generate authoritative live-peer or live-delivery proof.
- Recent history stores only redacted data by default.
- Full payload content may appear only in explicit export artifacts and only
  after operator opt-in.
- Session history and live session state remain visibly separate.
- Retained-history pruning removes the oldest session records once the index
  would exceed 20 sessions.

## State transitions

### Scenario surface state

`main` ↔ `advanced`  
`main`/`advanced` → `lab`  
`main`/`advanced` → `solo`

Rules:
- `lab` is always explicitly labeled as non-normative.
- `solo` can coexist with `main` or `advanced`, but not with authoritative live
  proof claims.

### Session lifecycle

`starting` → `live` → `ended` → `retained` → (`exported` or `deleted`)

Rules:
- A session cannot become `retained` until it has ended.
- Deleting a retained session removes it from `RecentSessionHistory`.
- Exporting a session does not remove it from retained history automatically.

### Export payload policy

`metadata-only` → `redacted-preview` → `full-opt-in`

Rules:
- Default export starts at `redacted-preview`.
- Transition to `full-opt-in` requires explicit operator action.
- Retained history never upgrades itself to `full-opt-in` automatically.
