@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.oslog.meshlink_os_log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.os_log_create

/** The unified log of subsystem `ch.trancee.meshlink`, category `diagnostics`. */
internal class IosOsLog : OsLog {
    private val log = os_log_create("ch.trancee.meshlink", "diagnostics")

    override fun write(
        type: UByte,
        shown: String,
        hidden: String,
    ) {
        meshlink_os_log(log, type, shown, hidden)
    }
}
