package ch.trancee.meshlink

import ch.trancee.meshlink.ReferencePages.documented
import ch.trancee.meshlink.ReferencePages.markdown
import ch.trancee.meshlink.ReferencePages.sources
import ch.trancee.meshlink.ReferencePages.symbols
import java.io.File
import kotlin.test.assertEquals

/**
 * The diagnostic catalog as the KDoc of `DiagnosticEvent.kt` writes it (ADR 0020): each event's code,
 * severity, meaning, guidance, fields and values, in source order. The diagnostic reference and the
 * troubleshooting pages are generated from it.
 */
internal object DiagnosticCatalog {
    class Entry(
        val name: String,
        val doc: String,
        val type: String? = null,
    )

    class Event(
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
        val cause = guidance.single { it.first == "Cause" }.second
    }

    class Catalog(
        val severities: List<Entry>,
        val events: List<Event>,
    )

    private val LABELS = listOf("Cause", "Fix", "Related", "Symptom")
    private val EVENT = documented("""public class (\w+) internal constructor\(""")
    private val HEADER = Regex("""\) : DiagnosticEvent\("(ML\d{4})", Severity\.(\w+)\)""")
    private val FIELD = documented("""public val (\w+): ([\w.]+)""")
    private val ENUM = Regex("""public enum class (\w+) \{(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL)
    private val CONSTANT = documented("""([A-Z]\w*),""")

    /** The catalog of the committed `DiagnosticEvent.kt`. */
    fun read(): Catalog = catalog(File(sources, "DiagnosticEvent.kt").readText())

    // Each event of the sealed class, in source order, and the severities.
    private fun catalog(source: String): Catalog {
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
    private fun event(
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
    private fun guidance(lines: List<String>): List<Pair<String, String>> =
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

    private fun constants(body: String): List<Entry> =
        CONSTANT.findAll(body).map { Entry(it.groupValues[2], markdown(it.groupValues[1])) }.toList()
}
