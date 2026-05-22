package ch.trancee.meshlink.reference.session

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun formatExportTimestampUtc(epochMillis: Long): String {
    val localDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    val year = localDateTime.year
    require(year in 0..9999) {
        "Export timestamp year must be between 0000 and 9999 for the session-artifact contract"
    }
    val milliseconds = localDateTime.nanosecond / 1_000_000
    return buildString(EXPORT_TIMESTAMP_LENGTH) {
        appendPadded(year, 4)
        append('-')
        appendPadded(localDateTime.month.ordinal + 1, 2)
        append('-')
        appendPadded(localDateTime.day, 2)
        append('T')
        appendPadded(localDateTime.hour, 2)
        append(':')
        appendPadded(localDateTime.minute, 2)
        append(':')
        appendPadded(localDateTime.second, 2)
        append('.')
        appendPadded(milliseconds, 3)
        append('Z')
    }
}

private fun StringBuilder.appendPadded(value: Int, width: Int): Unit {
    append(value.toString().padStart(width, '0'))
}

private const val EXPORT_TIMESTAMP_LENGTH: Int = 24
