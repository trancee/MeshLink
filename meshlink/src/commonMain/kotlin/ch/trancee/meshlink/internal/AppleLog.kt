package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.Severity

/** Apple's unified log, `os_log`: writes `shown` as a public and `hidden` as a private argument. */
internal fun interface OsLog {
    fun write(
        type: UByte,
        shown: String,
        hidden: String,
    )
}

/**
 * Writes diagnostics to [osLog] at the `os_log_type_t` of their severity. A warning is `default`,
 * which the unified log persists, as `fault` is for bugs in the caller, not for radio failures.
 */
internal class AppleLog(
    private val osLog: OsLog,
) : PlatformLog {
    override fun write(
        severity: Severity,
        shown: String,
        hidden: String,
    ) {
        osLog.write(type(severity), shown, hidden)
    }

    private fun type(severity: Severity): UByte =
        when (severity) {
            Severity.Debug -> OS_LOG_TYPE_DEBUG
            Severity.Info -> OS_LOG_TYPE_INFO
            Severity.Warning -> OS_LOG_TYPE_DEFAULT
            Severity.Error -> OS_LOG_TYPE_ERROR
        }

    private companion object {
        const val OS_LOG_TYPE_DEFAULT: UByte = 0x00u
        const val OS_LOG_TYPE_INFO: UByte = 0x01u
        const val OS_LOG_TYPE_DEBUG: UByte = 0x02u
        const val OS_LOG_TYPE_ERROR: UByte = 0x10u
    }
}
