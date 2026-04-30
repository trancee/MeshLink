package ch.trancee.meshlink.tui.core

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pure-Java terminal backend. No JLine dependency.
 *
 * Uses stty for raw mode and a background reader thread for non-blocking input.
 * Supports two modes:
 * - /dev/tty direct access (when available)
 * - stdin/stdout (fallback, e.g. Gradle with standardInput = System.in)
 */
class JvmTerminalBackend : AutoCloseable {
    private val inputStream: java.io.InputStream
    private val writer: PrintWriter
    private val useTty: Boolean
    private val inputQueue = ArrayBlockingQueue<Int>(256)
    private val readerThread: Thread

    init {
        // Try /dev/tty first, fall back to stdin/stdout
        val (ins, outs, tty) = try {
            val ttyIn = FileInputStream("/dev/tty")
            val ttyOut = FileOutputStream("/dev/tty")
            Triple(ttyIn as java.io.InputStream, PrintWriter(ttyOut, false), true)
        } catch (_: Exception) {
            Triple(System.`in`, PrintWriter(System.out, false), false)
        }
        inputStream = ins
        writer = outs
        useTty = tty

        // Background thread pumps bytes into a queue for non-blocking reads
        readerThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val b = inputStream.read()
                    if (b == -1) break
                    inputQueue.offer(b)
                }
            } catch (_: Exception) {
                // Stream closed — exit silently
            }
        }, "tui-input-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun size(): Pair<Int, Int> {
        // Try stty size
        val cmd = if (useTty) "stty size < /dev/tty" else "stty size"
        val proc = ProcessBuilder("/bin/sh", "-c", cmd)
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        val parts = output.split(" ")
        if (parts.size == 2) {
            val rows = parts[0].toIntOrNull() ?: 24
            val cols = parts[1].toIntOrNull() ?: 80
            return cols to rows
        }
        // Try tput as fallback
        return try {
            val cols = Runtime.getRuntime().exec(arrayOf("tput", "cols"))
                .inputStream.bufferedReader().readText().trim().toInt()
            val rows = Runtime.getRuntime().exec(arrayOf("tput", "lines"))
                .inputStream.bufferedReader().readText().trim().toInt()
            cols to rows
        } catch (_: Exception) {
            80 to 24
        }
    }

    fun enterRawMode() {
        if (useTty) {
            // Configure /dev/tty directly
            ProcessBuilder("/bin/sh", "-c", "stty raw -echo < /dev/tty")
                .start().waitFor()
        } else {
            // Configure stdin — inheritIO passes our stdin to the stty child process
            ProcessBuilder("stty", "raw", "-echo")
                .inheritIO()
                .start().waitFor()
        }
    }

    private fun exitRawMode() {
        if (useTty) {
            ProcessBuilder("/bin/sh", "-c", "stty sane < /dev/tty")
                .start().waitFor()
        } else {
            ProcessBuilder("stty", "sane")
                .inheritIO()
                .start().waitFor()
        }
    }

    fun enterAlternateScreen() { esc("?1049h") }
    fun exitAlternateScreen() { esc("?1049l") }
    fun hideCursor() { esc("?25l") }
    fun showCursor() { esc("?25h") }
    fun moveCursor(x: Int, y: Int) { esc("${y + 1};${x + 1}H") }
    fun setStyle(style: Style) { writer.write(style.toAnsiSequence()) }
    fun resetStyle() { esc("0m") }
    fun print(text: String) { writer.write(text) }
    fun clear() { esc("2J"); esc("1;1H") }
    fun flush() { writer.flush() }

    /**
     * Non-blocking event poll. Returns null on timeout.
     */
    fun pollEvent(timeoutMillis: Long): KeyEvent? {
        val first = inputQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        return parseInput(first)
    }

    private fun parseInput(first: Int): KeyEvent {
        if (first != 27) return keyFromByte(first)
        // Wait briefly for rest of escape sequence
        val second = inputQueue.poll(80, TimeUnit.MILLISECONDS) ?: return KeyEvent(KeyCode.Escape)
        if (second != '['.code) return KeyEvent(KeyCode.Char(second.toChar()), alt = true)

        // CSI sequence: ESC [ <params> <terminator>
        val params = StringBuilder()
        while (true) {
            val b = inputQueue.poll(80, TimeUnit.MILLISECONDS) ?: return KeyEvent(KeyCode.Escape)
            val ch = b.toChar()
            if (ch in '0'..'9' || ch == ';') {
                params.append(ch)
            } else {
                return parseCsi(ch, params.toString())
            }
        }
    }

    private fun parseCsi(terminator: Char, params: String): KeyEvent {
        val code: KeyCode = when (terminator) {
            'A' -> KeyCode.Up
            'B' -> KeyCode.Down
            'C' -> KeyCode.Right
            'D' -> KeyCode.Left
            'H' -> KeyCode.Home
            'F' -> KeyCode.End
            'Z' -> KeyCode.BackTab
            '~' -> when (params.substringBefore(';').toIntOrNull()) {
                3 -> KeyCode.Delete
                5 -> KeyCode.PageUp
                6 -> KeyCode.PageDown
                else -> KeyCode.Escape
            }
            else -> KeyCode.Escape
        }
        return KeyEvent(code)
    }

    private fun keyFromByte(byte: Int): KeyEvent = when (byte) {
        9 -> KeyEvent(KeyCode.Tab)
        10, 13 -> KeyEvent(KeyCode.Enter)
        27 -> KeyEvent(KeyCode.Escape)
        127 -> KeyEvent(KeyCode.Backspace)
        in 1..26 -> KeyEvent(KeyCode.Char(('a' + byte - 1)), ctrl = true)
        else -> KeyEvent(KeyCode.Char(byte.toChar()))
    }

    private fun esc(code: String) { writer.write("\u001b[$code") }

    override fun close() {
        // exitRawMode FIRST so terminal is usable again, then halt() handles the rest.
        // Do NOT close inputStream — on macOS, closing a FileInputStream while another
        // thread is blocked in read() on the same stream causes a deadlock.
        exitRawMode()
        readerThread.interrupt()
    }
}

data class KeyEvent(val code: KeyCode, val ctrl: Boolean = false, val alt: Boolean = false)

sealed interface KeyCode {
    data class Char(val c: kotlin.Char) : KeyCode
    data object Enter : KeyCode
    data object Escape : KeyCode
    data object Backspace : KeyCode
    data object Tab : KeyCode
    data object BackTab : KeyCode
    data object Up : KeyCode
    data object Down : KeyCode
    data object Left : KeyCode
    data object Right : KeyCode
    data object Home : KeyCode
    data object End : KeyCode
    data object PageUp : KeyCode
    data object PageDown : KeyCode
    data object Delete : KeyCode
}
