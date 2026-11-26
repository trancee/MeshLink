package ch.trancee.meshlink.internal

import android.util.Log
import ch.trancee.meshlink.DiagnosticEvent.Severity

/** Writes diagnostics to logcat under the tag `MeshLink`, at the priority of their severity. */
internal object AndroidLog : PlatformLog {
    private const val TAG = "MeshLink"

    override fun write(
        severity: Severity,
        shown: String,
        hidden: String,
    ) {
        Log.println(priority(severity), TAG, if (hidden.isEmpty()) shown else "$shown $hidden")
    }

    private fun priority(severity: Severity): Int =
        when (severity) {
            Severity.Debug -> Log.DEBUG
            Severity.Info -> Log.INFO
            Severity.Warning -> Log.WARN
            Severity.Error -> Log.ERROR
        }
}
