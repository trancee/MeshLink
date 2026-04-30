package ch.trancee.meshlink.tui.core

/**
 * Double-buffered terminal renderer with diff-flush.
 */
class TerminalRenderer(private val backend: JvmTerminalBackend) {
    private var previousBuffer: Buffer? = null

    fun init() {
        backend.enterRawMode()
        backend.enterAlternateScreen()
        backend.hideCursor()
        backend.clear()
        backend.flush()
    }

    fun restore() {
        backend.showCursor()
        backend.exitAlternateScreen()
        backend.flush()
    }

    fun draw(render: (Buffer, Rect) -> Unit) {
        val (cols, rows) = backend.size()
        val buffer = Buffer(cols, rows)
        val area = Rect(0, 0, cols, rows)
        render(buffer, area)

        val prev = previousBuffer
        if (prev == null || prev.width != cols || prev.height != rows) {
            backend.clear()
            renderFull(buffer)
        } else {
            renderDiff(buffer, prev)
        }
        backend.flush()
        previousBuffer = buffer.snapshot()
    }

    fun pollEvent(timeoutMillis: Long): KeyEvent? = backend.pollEvent(timeoutMillis)

    private fun renderFull(buf: Buffer) {
        var lastStyle = Style.DEFAULT
        for (y in 0 until buf.height) {
            backend.moveCursor(0, y)
            for (x in 0 until buf.width) {
                val cell = buf[x, y]
                if (cell.style != lastStyle) {
                    backend.setStyle(cell.style)
                    lastStyle = cell.style
                }
                backend.print(cell.symbol)
            }
        }
        backend.resetStyle()
    }

    private fun renderDiff(current: Buffer, prev: Buffer) {
        var lastStyle: Style? = null
        for ((x, y, cell) in current.diff(prev)) {
            backend.moveCursor(x, y)
            if (cell.style != lastStyle) {
                backend.setStyle(cell.style)
                lastStyle = cell.style
            }
            backend.print(cell.symbol)
        }
        if (lastStyle != null) backend.resetStyle()
    }
}
