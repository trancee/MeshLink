package ch.trancee.meshlink.transport

actual object Logger {
    actual fun d(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
    }
}
