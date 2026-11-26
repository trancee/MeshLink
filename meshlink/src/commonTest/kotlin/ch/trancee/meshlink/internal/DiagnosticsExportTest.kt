package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticCounters
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.DiagnosticsSnapshot
import ch.trancee.meshlink.LinkSnapshot
import ch.trancee.meshlink.PeerRef
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.QueueCounts
import ch.trancee.meshlink.SecurityProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsExportTest {
    @Test
    fun aRunningNodeExportsItsVersionsSnapshotAndEvents() {
        // Arrange
        val peer = PeerRef(0x0a0b0c0d)
        val links =
            listOf(
                LinkSnapshot(peer, LinkSnapshot.Bearer.L2capCoc, LinkSnapshot.Phy.Le2M, 247, Role.Central),
                LinkSnapshot(PeerRef(1), LinkSnapshot.Bearer.Gatt, null, 23, Role.Peripheral),
            )
        val counters = DiagnosticCounters(mapOf("ML1001" to 1L, "ML3002" to 2L), 5, 6, 7)
        val snapshot =
            DiagnosticsSnapshot(
                listOf("meshId = <redacted> (override)"),
                SecurityProfile.Unsecured,
                DensityClass.Medium,
                links,
                QueueCounts(1, 2, 3, 4),
                counters,
            )
        val closed = DiagnosticEvent.LinkClosed(peer, DiagnosticEvent.LinkClosed.Reason.ConnectionLost, false, 8)
        closed.count = 2
        val history =
            listOf(
                DiagnosticsHub.Recorded(DiagnosticEvent.Started(SecurityProfile.Unsecured, Preset.OfflineChat), 1_000),
                DiagnosticsHub.Recorded(closed, 2_000),
            )

        // Act
        val json = DiagnosticsExport.encode(HEADER, snapshot, history)

        // Assert
        assertEquals(RUNNING, json)
    }

    @Test
    fun aStoppedNodeExportsNullsAndEmptyCollections() {
        // Arrange
        val snapshot =
            DiagnosticsSnapshot(
                emptyList(),
                SecurityProfile.Unsecured,
                null,
                emptyList(),
                QueueCounts(0, 0, 0, 0),
                DiagnosticCounters(emptyMap(), 0, 0, 0),
            )

        // Act
        val json = DiagnosticsExport.encode(HEADER, snapshot, emptyList())

        // Assert
        assertEquals(STOPPED, json)
    }

    @Test
    fun aStringEscapesQuotesBackslashesAndControlCharacters() {
        // Arrange
        val line = "say \"hi\" \\ new\nline\rtab\there\u0001end"
        val snapshot =
            DiagnosticsSnapshot(
                listOf(line),
                SecurityProfile.Unsecured,
                null,
                emptyList(),
                QueueCounts(0, 0, 0, 0),
                DiagnosticCounters(emptyMap(), 0, 0, 0),
            )

        // Act
        val json = DiagnosticsExport.encode(HEADER, snapshot, emptyList())

        // Assert
        val escaped = """"say \"hi\" \\ new\nline\rtab\there\u0001end""""
        assertEquals("    \"configuration\": [\n      $escaped\n    ],", json.lines().subList(7, 10).joinToString("\n"))
    }

    private companion object {
        val HEADER = ExportHeader("0.1.0-test", 1, "Android", "15 (API 35)")

        val RUNNING =
            """
            {
              "format": 1,
              "meshlink": "0.1.0-test",
              "protocol": 1,
              "platform": "Android",
              "os": "15 (API 35)",
              "snapshot": {
                "configuration": [
                  "meshId = <redacted> (override)"
                ],
                "profile": "Unsecured",
                "densityClass": "Medium",
                "links": [
                  {
                    "peer": "peer-0a0b0c0d",
                    "bearer": "L2capCoc",
                    "phy": "Le2M",
                    "mtu": 247,
                    "role": "Central"
                  },
                  {
                    "peer": "peer-00000001",
                    "bearer": "Gatt",
                    "phy": null,
                    "mtu": 23,
                    "role": "Peripheral"
                  }
                ],
                "queues": {
                  "outgoing": 1,
                  "held": 2,
                  "inbox": 3,
                  "certificates": 4
                },
                "counters": {
                  "events": {
                    "ML1001": 1,
                    "ML3002": 2
                  },
                  "framesSent": 5,
                  "framesRelayed": 6,
                  "handshakesSucceeded": 7
                }
              },
              "events": [
                {
                  "code": "ML1001",
                  "name": "Started",
                  "severity": "Info",
                  "count": 1,
                  "fields": {
                    "profile": "Unsecured",
                    "preset": "OfflineChat"
                  },
                  "at": 1000
                },
                {
                  "code": "ML3002",
                  "name": "LinkClosed",
                  "severity": "Info",
                  "count": 2,
                  "fields": {
                    "peer": "peer-0a0b0c0d",
                    "reason": "ConnectionLost",
                    "byPeer": false,
                    "platformCode": 8
                  },
                  "at": 2000
                }
              ]
            }
            """.trimIndent()

        val STOPPED =
            """
            {
              "format": 1,
              "meshlink": "0.1.0-test",
              "protocol": 1,
              "platform": "Android",
              "os": "15 (API 35)",
              "snapshot": {
                "configuration": [],
                "profile": "Unsecured",
                "densityClass": null,
                "links": [],
                "queues": {
                  "outgoing": 0,
                  "held": 0,
                  "inbox": 0,
                  "certificates": 0
                },
                "counters": {
                  "events": {},
                  "framesSent": 0,
                  "framesRelayed": 0,
                  "handshakesSucceeded": 0
                }
              },
              "events": []
            }
            """.trimIndent()
    }
}

private typealias Role = DiagnosticEvent.LinkOpened.Role
