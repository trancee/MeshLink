package ch.trancee.meshlink.transport

import platform.Foundation.NSLog

actual object Logger {
    actual fun d(tag: String, msg: String) {
        NSLog("[$tag] $msg")
    }
}
