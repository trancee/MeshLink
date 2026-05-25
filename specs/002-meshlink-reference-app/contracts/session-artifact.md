# Contract: Session artifact export

## Purpose

Define the exported session-artifact structure for the MeshLink reference app.

## Format

- Encoding: UTF-8 JSON
- Scope: one exported artifact per export action
- Export-contract version: `1`
- Default policy: payload metadata plus redacted previews
- Elevated policy: full payload content only after explicit operator opt-in
- Retained-session exports remain redacted because retained history does not
  persist full payload content
- Pre-release local artifacts generated before this contract stabilized are not
  compatibility targets

## Timestamp rules

All exported timestamp fields use UTC ISO 8601 strings in exact
`YYYY-MM-DDTHH:MM:SS.SSSZ` form.

Optional timestamp fields are absent when unknown.

## Top-level fields

| Field | Type | Required | Description |
|---|---|---:|---|
| `artifactVersion` | string | Yes | Always `1` for the unreleased v1 export contract |
| `artifactId` | string | Yes | Unique export identifier |
| `createdAt` | string | Yes | UTC ISO 8601 export timestamp |
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
| `surface` | string | Yes | `main`, `advanced`, `solo`, or `lab` |
| `authorityMode` | string | Yes | `live` or `solo` |
| `startedAt` | string | Yes | UTC ISO 8601 session start timestamp |
| `endedAt` | string | No | UTC ISO 8601 session end timestamp when the session has ended |
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
| `occurredAt` | string | Yes | UTC ISO 8601 event timestamp |
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

## Example

```json
{
  "artifactVersion": "1",
  "artifactId": "artifact-android-1747922597123",
  "createdAt": "2026-05-22T14:03:17.123Z",
  "sourceSessionId": "android-1747922579000",
  "scenario": {
    "scenarioId": "guided-first-exchange",
    "title": "Guided first exchange",
    "surface": "main",
    "authorityMode": "live",
    "startedAt": "2026-05-22T14:02:59.000Z",
    "lastOutcomeSummary": "Message sent"
  },
  "configuration": {
    "platform": "Android",
    "surface": "main-guided",
    "appId": "demo.meshlink.reference"
  },
  "peerSummaries": [
    {
      "peerSuffix": "654321",
      "trustState": "Trusted",
      "connectionState": "Connected",
      "lastDeliveryOutcome": "Message sent"
    }
  ],
  "timelineEntries": [
    {
      "entryId": "android-1747922579000-7",
      "occurredAt": "2026-05-22T14:03:10.456Z",
      "family": "message",
      "severity": "success",
      "title": "Guided message sent",
      "detail": "First guided payload reached 654321 with NORMAL priority.",
      "peerSuffix": "654321",
      "payloadMetadata": {
        "sizeBytes": "19",
        "contentType": "text/plain"
      },
      "payloadPreview": "he… [redacted]"
    }
  ],
  "payloadPolicy": {
    "defaultMode": "redacted-preview",
    "fullPayloadIncluded": false,
    "operatorOptInRecorded": false
  }
}
```

## Validation rules

- `createdAt`, `startedAt`, `endedAt`, and `occurredAt` must use exact UTC ISO
  8601 `YYYY-MM-DDTHH:MM:SS.SSSZ` formatting when present.
- Optional timestamp fields must be absent when unknown.
- `fullPayloadIncluded = true` requires `operatorOptInRecorded = true`.
- When `fullPayloadIncluded = false`, `fullPayload` must be absent from all
  timeline entries.
- `peerSummaries` use redacted peer identifiers by default.
- Retained-session exports must keep `fullPayloadIncluded = false` because
  retained history strips sensitive payload content.
- `authorityMode = solo` must not be exported as authoritative live proof.
- `surface = solo` must preserve the non-authoritative solo exploration identity
  in the exported scenario block.
- `surface = lab` must preserve a non-normative indicator in the exported
  scenario block.
