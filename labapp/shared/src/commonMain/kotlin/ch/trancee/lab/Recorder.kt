package ch.trancee.lab

import kotlin.time.TimeMark

/**
 * Writes one JSON object per [record] call through [write]: `t_ms`, the milliseconds since [start] on
 * the platform's monotonic clock, then `event`, then the fields.
 */
internal class Recorder(
    private val write: (String) -> Unit,
    private val start: TimeMark,
) {
    fun record(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val all = listOf("t_ms" to start.elapsedNow().inWholeMilliseconds, "event" to event) + fields
        write(all.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${json(value)}" })
    }

    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean, is Int, is Long -> value.toString()
            is List<*> -> value.joinToString(",", "[", "]") { json(it) }
            else -> quote(value.toString())
        }

    private fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { char ->
                when {
                    char == '"' || char == '\\' -> append('\\').append(char)
                    char < ' ' -> append("\\u").append(char.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                    else -> append(char)
                }
            }
            append('"')
        }

    private companion object {
        const val HEX = 16
        const val UNICODE_DIGITS = 4
    }
}
