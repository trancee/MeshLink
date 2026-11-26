package ch.trancee.meshlink

import ch.trancee.meshlink.ReferencePages.compare
import ch.trancee.meshlink.ReferencePages.documented
import ch.trancee.meshlink.ReferencePages.markdown
import ch.trancee.meshlink.ReferencePages.sources
import ch.trancee.meshlink.internal.DiagnosticsExport
import ch.trancee.meshlink.internal.DiagnosticsHub
import ch.trancee.meshlink.internal.ExportHeader
import org.junit.Test
import java.io.File

/**
 * Generates `docs/reference/diagnostics-export.md` (ADR 0020 decision 6, ADR 0006 decision 3): the snapshot
 * fields from the KDoc of `DiagnosticsSnapshot.kt`, and an example written by the export's own encoder.
 * It fails when the committed page differs; `./gradlew updateReference` rewrites it.
 */
class DiagnosticsExportReferenceTest {
    private val source = File(sources, "DiagnosticsSnapshot.kt").readText()

    @Test
    fun theExportReferenceMatchesTheEncoder() = compare(PAGE, page())

    private fun page(): String =
        listOf(
            "# Diagnostics export",
            "",
            ReferencePages.generated("DiagnosticsExportReferenceTest"),
            "",
            "`MeshLink.diagnostics.export()` returns this JSON document for a bug report. It holds nothing a " +
                "[diagnostic event](diagnostics.md) or `MeshLink.diagnostics.snapshot()` doesn't show: no key, " +
                "payload, NodeId, mesh ID or Bluetooth address. A peer appears as a `PeerRef`, which changes on " +
                "every `start()`. Keys keep the order below; `format` grows only when a field changes meaning or " +
                "goes, and a reader ignores keys it doesn't know.",
            "",
            "## Document",
            "",
            TOP_LEVEL,
            "",
            section("snapshot", "DiagnosticsSnapshot"),
            section("snapshot.links[]", "LinkSnapshot"),
            section("snapshot.queues", "QueueCounts"),
            section("snapshot.counters", "DiagnosticCounters"),
            "## events[]",
            "",
            EVENT,
            "",
            "## Example",
            "",
            "```json",
            DiagnosticsExport.encode(HEADER, snapshot(), history()),
            "```",
        ).joinToString("\n") + "\n"

    // The properties of the public class [name] as a list of `key` (type): meaning.
    private fun section(
        key: String,
        name: String,
    ): String {
        val body = source.substringAfter("public class $name internal constructor(").substringBefore("\n)") + "\n"
        val fields =
            FIELD.findAll(body).joinToString("\n") { field ->
                val (doc, property, type) = field.destructured
                "- `$property` (`$type`): ${markdown(doc)}"
            }
        return "## $key\n\n$fields\n"
    }

    private fun snapshot(): DiagnosticsSnapshot {
        val links =
            listOf(
                LinkSnapshot(PeerRef(0x1f2e3d4c), LinkSnapshot.Bearer.L2capCoc, LinkSnapshot.Phy.Le2M, 247, CENTRAL),
                LinkSnapshot(PeerRef(0x5a6b7c8d), LinkSnapshot.Bearer.Gatt, null, 185, PERIPHERAL),
            )
        val counters = DiagnosticCounters(mapOf("ML1001" to 1L, "ML3001" to 2L, "ML3002" to 1L), 42, 17, 2)
        val configuration = listOf("meshId = <redacted> (override)", "meshKey = none (default)")
        return DiagnosticsSnapshot(
            configuration,
            SecurityProfile.Classical,
            DensityClass.Medium,
            links,
            QueueCounts(1, 0, 0, 3),
            counters,
        )
    }

    private fun history(): List<DiagnosticsHub.Recorded> {
        val lost = DiagnosticEvent.LinkClosed(PeerRef(0x0a0b0c0d), LOST, false, 8)
        return listOf(
            DiagnosticsHub.Recorded(DiagnosticEvent.Started(SecurityProfile.Classical, Preset.OfflineChat), START),
            DiagnosticsHub.Recorded(lost, START + 61_000),
        )
    }

    private companion object {
        const val PAGE = "docs/reference/diagnostics-export.md"
        const val START = 1_764_237_600_000
        val CENTRAL = DiagnosticEvent.LinkOpened.Role.Central
        val PERIPHERAL = DiagnosticEvent.LinkOpened.Role.Peripheral
        val LOST = DiagnosticEvent.LinkClosed.Reason.ConnectionLost
        val HEADER = ExportHeader("0.1.0", 1, "Android", "15 (API 35)")
        val FIELD = documented("""public val (\w+): ([^\n]+),\n""")

        val TOP_LEVEL =
            """
            - `format` (number): the version of the document's shape, now `${DiagnosticsExport.FORMAT}`.
            - `meshlink` (string): the MeshLink version.
            - `protocol` (number): the protocol version the node implements (spec `protocol-version`).
            - `platform` (string): `Android` or `iOS`.
            - `os` (string): the OS version, such as `15 (API 35)` on Android or `18.5.0` on iOS.
            - `snapshot` (object): the node's state when it exported, below.
            - `events` (array): the last 500 events the node emitted since it was created, oldest first, below.
            """.trimIndent()

        val EVENT =
            """
            - `code` (string): the event's code, such as `ML3002`; see [Diagnostics](diagnostics.md).
            - `name` (string): the event's name, such as `LinkClosed`.
            - `severity` (string): `Debug`, `Info`, `Warning` or `Error`.
            - `count` (number): how many reports the event stands for after coalescing.
            - `fields` (object): the event's fields by name, as its `toString()` shows them.
            - `at` (number): when the node emitted the event, in milliseconds since the Unix epoch.
            """.trimIndent()
    }
}
