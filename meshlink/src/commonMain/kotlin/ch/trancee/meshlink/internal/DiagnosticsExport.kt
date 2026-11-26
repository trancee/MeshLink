package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticsSnapshot
import ch.trancee.meshlink.LinkSnapshot

/** What an export says about the software it came from. */
internal class ExportHeader(
    val meshlink: String,
    val protocol: Int,
    val platform: String,
    val os: String,
)

/**
 * Writes a node's diagnostics as the JSON document `docs/reference/diagnostics-export.md` describes,
 * indented by two spaces (ADR 0020 decision 6). Numbers and Booleans stay JSON numbers and Booleans;
 * any other value is its `toString()`.
 */
internal object DiagnosticsExport {
    /** The version of the document's shape; it grows only when a field changes meaning or goes. */
    const val FORMAT = 1

    fun encode(
        header: ExportHeader,
        snapshot: DiagnosticsSnapshot,
        history: List<DiagnosticsHub.Recorded>,
    ): String {
        val document =
            linkedMapOf(
                "format" to FORMAT,
                "meshlink" to header.meshlink,
                "protocol" to header.protocol,
                "platform" to header.platform,
                "os" to header.os,
                "snapshot" to snapshot(snapshot),
                "events" to history.map(::event),
            )
        return StringBuilder().apply { write(document, "") }.toString()
    }

    private fun snapshot(snapshot: DiagnosticsSnapshot): Map<String, Any?> {
        val queues = snapshot.queues
        val counters = snapshot.counters
        return linkedMapOf(
            "configuration" to snapshot.configuration,
            "profile" to snapshot.profile,
            "densityClass" to snapshot.densityClass,
            "links" to snapshot.links.map(::link),
            "queues" to
                linkedMapOf(
                    "outgoing" to queues.outgoing,
                    "held" to queues.held,
                    "inbox" to queues.inbox,
                    "certificates" to queues.certificates,
                ),
            "counters" to
                linkedMapOf(
                    "events" to counters.events,
                    "framesSent" to counters.framesSent,
                    "framesRelayed" to counters.framesRelayed,
                    "handshakesSucceeded" to counters.handshakesSucceeded,
                ),
        )
    }

    private fun link(link: LinkSnapshot): Map<String, Any?> =
        with(link) { linkedMapOf("peer" to peer, "bearer" to bearer, "phy" to phy, "mtu" to mtu, "role" to role) }

    private fun event(recorded: DiagnosticsHub.Recorded): Map<String, Any?> {
        val event = recorded.event
        return linkedMapOf(
            "code" to event.code,
            "name" to "${event::class.simpleName}",
            "severity" to event.severity,
            "count" to event.count,
            "fields" to event.fields.toMap(),
            "at" to recorded.atMillis,
        )
    }

    private fun StringBuilder.write(
        value: Any?,
        indent: String,
    ) {
        when (value) {
            null, is Boolean, is Int, is Long -> {
                append(value)
            }

            is Map<*, *> -> {
                block('{', '}', value.entries, indent) {
                    write(it.key, indent)
                    append(": ")
                    it.value
                }
            }

            is List<*> -> {
                block('[', ']', value, indent) { it }
            }

            else -> {
                string(value.toString())
            }
        }
    }

    /** Writes [items] between [open] and [close], one per line; [entry] writes any key and returns the value. */
    private fun <T> StringBuilder.block(
        open: Char,
        close: Char,
        items: Collection<T>,
        indent: String,
        entry: StringBuilder.(T) -> Any?,
    ) {
        append(open)
        val inner = "$indent  "
        items.forEachIndexed { index, item ->
            append(if (index == 0) "\n" else ",\n").append(inner)
            write(entry(item), inner)
        }
        if (items.isNotEmpty()) append('\n').append(indent)
        append(close)
    }

    private fun StringBuilder.string(text: String) {
        append('"')
        text.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                else -> append(char)
            }
        }
        append('"')
    }

    private const val HEX = 16
    private const val UNICODE_DIGITS = 4
}
