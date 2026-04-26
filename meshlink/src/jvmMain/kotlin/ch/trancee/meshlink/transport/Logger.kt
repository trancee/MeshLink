package ch.trancee.meshlink.transport

actual object Logger {
    actual fun d(tag: String, msg: String) {
        println("[$tag] $msg")
    }
}
