package ch.trancee.meshlink

import ch.trancee.meshlink.ReferencePages.compare
import ch.trancee.meshlink.ReferencePages.documented
import ch.trancee.meshlink.ReferencePages.markdown
import ch.trancee.meshlink.ReferencePages.sources
import ch.trancee.meshlink.ReferencePages.symbols
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Generates the diagnostic index from the KDoc of `DiagnosticEvent.kt` (ADR 0020, ADR 0006 decision 3):
 * `docs/reference/diagnostics.md` for people, with one anchor per code, and `docs/agents/diagnostics.json`
 * for agents. It fails when a committed file differs; `./gradlew updateReference` rewrites them.
 */
class DiagnosticReferenceTest {
    private val catalog = catalog(File(sources, "DiagnosticEvent.kt").readText())

    @Test
    fun theDiagnosticReferenceMatchesTheCatalog() = compare(PAGE, page())

    @Test
    fun theAgentDiagnosticIndexMatchesTheCatalog() = compare("docs/agents/diagnostics.json", json())

    private fun page(): String {
        val index =
            catalog.events.map { event ->
                "| [${event.code}](#${event.anchor}) | `${event.name}` | `${event.severity}` | ${event.symptom} |"
            }
        val sections =
            catalog.events.map { event ->
                val blocks =
                    listOf(
                        "## ${event.code}",
                        "`DiagnosticEvent.${event.name}`, severity `${event.severity}`: ${event.meaning}",
                        event.guidance.joinToString("\n") { (label, text) -> "- $label: $text" },
                    ) + event.fields.map { "Fields:\n\n${entries(event.fields)}" }.take(1) +
                        event.values.map { (type, values) -> "`${event.name}.$type` values:\n\n${entries(values)}" }
                blocks.joinToString("\n\n") + "\n"
            }
        return listOf(
            "# Diagnostics",
            "",
            GENERATED,
            "",
            "`MeshLink.diagnostics` emits each event below as a `DiagnosticEvent`. A code never changes " +
                "meaning and is never reused, and its anchor here is the code in lower case. " +
                "Each event's `toString()` shows its code, its name, its fields and its count; no field holds a " +
                "key, a payload, a NodeId or a Bluetooth address. A peer appears as a `PeerRef` and a link whose " +
                "peer is not yet known as a `LinkRef`; both change on every `start()`.",
            "",
            "Repeats of a code for the same peer or link within 10 s arrive as one event whose `count` says how " +
                "many it stands for. A collector that falls 256 events behind loses the oldest, then gets `ML7001`. " +
                "See [Configuration](configuration.md#diagnosticsdebug) for `diagnostics.debug`.",
            "",
            "## Severities",
            "",
            entries(catalog.severities),
            "",
            "## Index",
            "",
            "| Code | Event | Severity | Symptom |",
            "|---|---|---|---|",
            index.joinToString("\n"),
            "",
            sections.joinToString("\n"),
        ).joinToString("\n").trimEnd() + "\n"
    }

    private fun json(): String {
        val events =
            catalog.events.map { event ->
                linkedMapOf(
                    "code" to event.code,
                    "name" to event.name,
                    "severity" to event.severity,
                    "reference" to "$PAGE#${event.anchor}",
                    "meaning" to event.meaning,
                ) + event.guidance.associate { (label, text) -> label.lowercase() to text } +
                    linkedMapOf(
                        "fields" to
                            event.fields.map { field ->
                                linkedMapOf("name" to field.name, "type" to field.type, "meaning" to field.doc)
                            },
                        "values" to
                            event.values.map { (type, values) ->
                                linkedMapOf(
                                    "type" to "${event.name}.$type",
                                    "values" to values.map { linkedMapOf("name" to it.name, "meaning" to it.doc) },
                                )
                            },
                    )
            }
        val document =
            linkedMapOf(
                "generatedBy" to "DiagnosticReferenceTest; edit the KDoc in $SOURCE, then run $UPDATE",
                "reference" to PAGE,
                "severities" to catalog.severities.map { linkedMapOf("name" to it.name, "meaning" to it.doc) },
                "events" to events,
            )
        return encode(document, "") + "\n"
    }

    private fun entries(entries: List<Entry>): String =
        entries.joinToString("\n") { entry ->
            val type = entry.type?.let { " (`$it`)" }.orEmpty()
            "- `${entry.name}`$type: ${entry.doc}"
        }

    private class Entry(
        val name: String,
        val doc: String,
        val type: String? = null,
    )

    private class Event(
        val code: String,
        val name: String,
        val severity: String,
        val meaning: String,
        val guidance: List<Pair<String, String>>,
        val fields: List<Entry>,
        val values: List<Pair<String, List<Entry>>>,
    ) {
        val anchor = code.lowercase()
        val symptom = guidance.single { it.first == "Symptom" }.second
    }

    private class Catalog(
        val severities: List<Entry>,
        val events: List<Event>,
    )

    private companion object {
        const val SOURCE = "meshlink/src/commonMain/kotlin/ch/trancee/meshlink/DiagnosticEvent.kt"
        const val PAGE = "docs/reference/diagnostics.md"
        const val UPDATE = "./gradlew updateReference"
        val GENERATED = ReferencePages.generated("DiagnosticReferenceTest")
        val LABELS = listOf("Cause", "Fix", "Related", "Symptom")

        val EVENT = documented("""public class (\w+) internal constructor\(""")
        val HEADER = Regex("""\) : DiagnosticEvent\("(ML\d{4})", Severity\.(\w+)\)""")
        val FIELD = documented("""public val (\w+): ([\w.]+)""")
        val ENUM = Regex("""public enum class (\w+) \{(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL)
        val CONSTANT = documented("""([A-Z]\w*),""")

        // Each event of the sealed class, in source order, and the severities.
        fun catalog(source: String): Catalog {
            val body = source.substringBefore("\n}\n")
            val matches = EVENT.findAll(body).toList()
            val events =
                matches.mapIndexed { i, match ->
                    val end = matches.getOrNull(i + 1)?.range?.first ?: body.length
                    event(match.groupValues[2], match.groupValues[1], body.substring(match.range.last, end))
                }
            val severities = constants(checkNotNull(ENUM.find(body)).groupValues[2])
            return Catalog(severities, events)
        }

        // An event from its name, its KDoc and its declaration up to the next event.
        fun event(
            name: String,
            kdoc: String,
            declaration: String,
        ): Event {
            val (code, severity) = checkNotNull(HEADER.find(declaration)) { name }.destructured
            val lines = kdoc.lines().map { it.trim().removePrefix("*").trim() }
            val items = lines.indexOfFirst { it.startsWith("- ") }.takeIf { it >= 0 } ?: lines.size
            val prose = markdown(lines.take(items).joinToString("\n"))
            assertEquals("`$code`: ", prose.take(code.length + 4), "$name: KDoc opens with its code")
            val guidance = guidance(lines.drop(items))
            assertEquals(LABELS, guidance.map { it.first }, "$name: KDoc lists Cause, Fix, Related, Symptom")
            val values = ENUM.findAll(declaration).map { it.groupValues[1] to constants(it.groupValues[2]) }.toList()
            val nested = values.map { it.first }
            val fields =
                FIELD.findAll(declaration).map { field ->
                    val (doc, property, type) = field.destructured
                    Entry(property, markdown(doc), if (type in nested) "$name.$type" else type)
                }
            return Event(
                code = code,
                name = name,
                severity = severity,
                meaning = prose.drop(code.length + 4).replaceFirstChar { it.uppercase() },
                guidance = guidance,
                fields = fields.toList(),
                values = values,
            )
        }

        // The `- Label: text` items of a KDoc, each joined across its wrapped lines.
        fun guidance(lines: List<String>): List<Pair<String, String>> =
            lines
                .filter { it.isNotBlank() }
                .fold(listOf<String>()) { items, line ->
                    if (line.startsWith("- ")) {
                        items + line.drop(2)
                    } else {
                        items.dropLast(1) +
                            "${items.last()} $line"
                    }
                }.map { symbols(it.substringBefore(": ")) to symbols(it.substringAfter(": ")) }

        fun constants(body: String): List<Entry> =
            CONSTANT.findAll(body).map { Entry(it.groupValues[2], markdown(it.groupValues[1])) }.toList()

        // JSON with two-space indents, keys in insertion order.
        fun encode(
            value: Any?,
            indent: String,
        ): String {
            val inner = "$indent  "
            return when (value) {
                is Map<*, *> -> {
                    value.entries.joinToString(
                        ",\n",
                        "{\n",
                        "\n$indent}",
                    ) { (k, v) -> "$inner${quote(k.toString())}: ${encode(v, inner)}" }
                }

                is List<*> -> {
                    if (value.isEmpty()) {
                        "[]"
                    } else {
                        value.joinToString(",\n", "[\n", "\n$indent]") { "$inner${encode(it, inner)}" }
                    }
                }

                else -> {
                    quote(value.toString())
                }
            }
        }

        fun quote(text: String): String =
            text
                .fold(StringBuilder("\"")) { out, c ->
                    when {
                        c == '"' || c == '\\' -> out.append('\\').append(c)
                        c == '\n' -> out.append("\\n")
                        c < ' ' -> out.append("\\u%04x".format(c.code))
                        else -> out.append(c)
                    }
                }.append('"')
                .toString()
    }
}
