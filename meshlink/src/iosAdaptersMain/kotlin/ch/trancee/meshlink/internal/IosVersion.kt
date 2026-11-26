@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

/** The iOS version, `major.minor.patch`. */
internal fun iosVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { "$majorVersion.$minorVersion.$patchVersion" }
