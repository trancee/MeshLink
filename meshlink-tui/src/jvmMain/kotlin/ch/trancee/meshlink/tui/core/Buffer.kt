package ch.trancee.meshlink.tui.core

/**
 * Single terminal cell with symbol and style.
 */
class Cell(var symbol: String = " ", var style: Style = Style.DEFAULT) {
    fun reset() {
        symbol = " "
        style = Style.DEFAULT
    }

    override fun equals(other: Any?): Boolean =
        other is Cell && symbol == other.symbol && style == other.style

    override fun hashCode(): Int = 31 * symbol.hashCode() + style.hashCode()
}

/**
 * 2D grid of [Cell]s for rendering. Supports diff-based updates.
 */
class Buffer(val width: Int, val height: Int) {
    private val cells: Array<Cell> = Array(width * height) { Cell() }

    operator fun get(x: Int, y: Int): Cell {
        if (x !in 0 until width || y !in 0 until height) return Cell()
        return cells[y * width + x]
    }

    fun setString(x: Int, y: Int, text: String, style: Style) {
        if (y !in 0 until height) return
        var col = x
        for (ch in text) {
            if (col >= width) break
            if (col >= 0) {
                cells[y * width + col].symbol = ch.toString()
                cells[y * width + col].style = style
            }
            col++
        }
    }

    fun setStyle(area: Rect, style: Style) {
        forEachCell(area) { _, _, cell -> cell.style = style }
    }

    fun fill(area: Rect, symbol: String, style: Style) {
        forEachCell(area) { _, _, cell ->
            cell.symbol = symbol
            cell.style = style
        }
    }

    fun clear() {
        cells.forEach { it.reset() }
    }

    fun diff(prev: Buffer): Sequence<Triple<Int, Int, Cell>> = sequence {
        require(width == prev.width && height == prev.height)
        for (i in cells.indices) {
            if (cells[i] != prev.cells[i]) {
                yield(Triple(i % width, i / width, cells[i]))
            }
        }
    }

    fun snapshot(): Buffer {
        val copy = Buffer(width, height)
        for (i in cells.indices) {
            copy.cells[i].symbol = cells[i].symbol
            copy.cells[i].style = cells[i].style
        }
        return copy
    }

    private inline fun forEachCell(area: Rect, action: (Int, Int, Cell) -> Unit) {
        for (row in area.top until area.bottom.coerceAtMost(height)) {
            for (col in area.left until area.right.coerceAtMost(width)) {
                action(col, row, cells[row * width + col])
            }
        }
    }
}
