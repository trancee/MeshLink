# Contract: Session Artifact Export

## Purpose

This contract defines the exported session artifact structure for the MeshLink
reference app.

## Format

- Encoding: UTF-8 JSON
- Scope: one exported artifact per session export action
- Default policy: payload metadata plus redacted previews
- Elevated policy: full payload content only after explicit operator opt-in

## Top-Level Fields

| Field | Type | Required | Description |
|---|---|---:|---|
| `artifactVersion` | string | Yes | Version of the export contract |
| `artifactId` | string | Yes | Unique export identifier |
| `createdAt` | string | Yes | ISO 8601 timestamp |
| `sourceSessionId` | string | Yes | Session being exported |
| `scenario` | object | Yes | Scenario summary block |
| `configuration` | object | Yes | Configuration snapshot active for the session |
| `peerSummaries` | array | Yes | Redacted peer summaries |
| `timelineEntries` | array | Yes | Structured event timeline |
| `payloadPolicy` | object | Yes | Export redaction/full-payload policy |

## Scenario Block

| Field | Type | Required | Description |
|---|---|---:|---|
| `scenarioId` | string | Yes | Stable scenario identifier |
| `title` | string | Yes | Operator-facing scenario name |
| `surface` | string | Yes | `main`, `advanced`, or `lab` |
| `authorityMode` | string | Yes | `live` or `solo` |
| `startedAt` | string | Yes | ISO 8601 timestamp |
| `endedAt` | string | No | ISO 8601 timestamp when available |
| `lastOutcomeSummary` | string | No | Last high-level result |

## Peer Summary Block

| Field | Type | Required | Description |
|---|---|---:|---|
| `peerSuffix` | string | Yes | Default redacted peer identifier |
| `trustState` | string | Yes | Current or final trust state |
| `connectionState` | string | Yes | Current or final connection state |
| `lastDeliveryOutcome` | string | No | Latest delivery summary |

## Timeline Entry Block

| Field | Type | Required | Description |
|---|---|---:|---|
| `entryId` | string | Yes | Stable event identifier |
| `occurredAt` | string | Yes | ISO 8601 timestamp |
| `family` | string | Yes | Event family |
| `severity` | string | Yes | Event severity |
| `title` | string | Yes | Short event summary |
| `detail` | string | Yes | Expanded explanation |
| `peerSuffix` | string | No | Redacted peer context |
| `payloadMetadata` | object | No | Size, direction, and content-type hints |
| `payloadPreview` | string | No | Redacted preview shown by default |
| `fullPayload` | string | No | Full payload content when explicitly included |

## Payload Policy Block

| Field | Type | Required | Description |
|---|---|---:|---|
| `defaultMode` | string | Yes | Always `redacted-preview` |
| `fullPayloadIncluded` | boolean | Yes | Whether full payloads are present |
| `operatorOptInRecorded` | boolean | Yes | Whether the operator explicitly requested full payload export |

## Validation Rules

- `fullPayloadIncluded = true` requires `operatorOptInRecorded = true`.
- When `fullPayloadIncluded = false`, `fullPayload` must be absent from all
  timeline entries.
- `peerSummaries` must use redacted peer identifiers by default.
- `authorityMode = solo` must not be exported as authoritative live proof.
- `surface = lab` must preserve a non-normative indicator in the exported
  scenario block.
