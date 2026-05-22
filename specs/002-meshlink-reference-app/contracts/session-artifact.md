# Contract: Session artifact export

## Purpose

Define the exported session-artifact structure for the MeshLink reference app.

## Format

- Encoding: UTF-8 JSON
- Scope: one exported artifact per export action
- Default policy: payload metadata plus redacted previews
- Elevated policy: full payload content only after explicit operator opt-in
- Retained-session exports remain redacted because retained history does not
  persist full payload content

## Top-level fields

| Field | Type | Required | Description |
|---|---|---:|---|
| `artifactVersion` | string | Yes | Export-contract version |
| `artifactId` | string | Yes | Unique export identifier |
| `createdAt` | string | Yes | Export timestamp string (current implementation uses epoch-millis text) |
| `sourceSessionId` | string | Yes | Session being exported |
| `scenario` | object | Yes | Scenario summary block |
| `configuration` | object | Yes | Configuration snapshot active for the session |
| `peerSummaries` | array | Yes | Redacted peer summaries |
| `timelineEntries` | array | Yes | Structured event timeline |
| `payloadPolicy` | object | Yes | Export redaction/full-payload policy |

## Scenario block

| Field | Type | Required | Description |
|---|---|---:|---|
| `scenarioId` | string | Yes | Stable scenario identifier |
| `title` | string | Yes | Operator-facing scenario name |
| `surface` | string | Yes | `main`, `advanced`, or `lab` |
| `authorityMode` | string | Yes | `live` or `solo` |
| `startedAt` | string | Yes | Session timestamp string (current implementation uses epoch-millis text) |
| `endedAt` | string | No | Session timestamp string when available |
| `lastOutcomeSummary` | string | No | Operator-facing last high-level result |

## Peer summary block

| Field | Type | Required | Description |
|---|---|---:|---|
| `peerSuffix` | string | Yes | Default redacted peer identifier |
| `trustState` | string | Yes | Current or final operator-facing trust state label |
| `connectionState` | string | Yes | Current or final operator-facing connection state label |
| `lastDeliveryOutcome` | string | No | Latest delivery summary |

## Timeline entry block

| Field | Type | Required | Description |
|---|---|---:|---|
| `entryId` | string | Yes | Stable event identifier |
| `occurredAt` | string | Yes | Event timestamp string (current implementation uses epoch-millis text) |
| `family` | string | Yes | Event family |
| `severity` | string | Yes | Event severity |
| `title` | string | Yes | Short event summary |
| `detail` | string | Yes | Expanded explanation |
| `peerSuffix` | string | No | Redacted peer context |
| `payloadMetadata` | object | No | Size and content-type hints when known |
| `payloadPreview` | string | No | Redacted preview shown by default |
| `fullPayload` | string | No | Full payload content when explicitly included |

## Payload policy block

| Field | Type | Required | Description |
|---|---|---:|---|
| `defaultMode` | string | Yes | Always `redacted-preview` |
| `fullPayloadIncluded` | boolean | Yes | Whether full payloads are present |
| `operatorOptInRecorded` | boolean | Yes | Whether the operator explicitly requested full payload export |

## Validation rules

- `fullPayloadIncluded = true` requires `operatorOptInRecorded = true`.
- When `fullPayloadIncluded = false`, `fullPayload` must be absent from all
  timeline entries.
- `peerSummaries` use redacted peer identifiers by default.
- Retained-session exports must keep `fullPayloadIncluded = false` because
  retained history strips sensitive payload content.
- `authorityMode = solo` must not be exported as authoritative live proof.
- `surface = lab` must preserve a non-normative indicator in the exported
  scenario block.
