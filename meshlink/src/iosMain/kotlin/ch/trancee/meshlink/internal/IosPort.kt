package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

/** The port of this iOS installation: CoreBluetooth on a device, [UnsupportedRadioPort] on the simulator. */
internal expect fun iosPort(): PlatformPort

/** The iOS version, `major.minor.patch`. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { "$majorVersion.$minorVersion.$patchVersion" }
