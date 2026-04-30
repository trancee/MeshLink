package ch.trancee.meshlink.tui.widgets

import ch.trancee.meshlink.tui.core.*

/**
 * Block widget draws a bordered box with optional title.
 */
class Block(
    val title: String? = null,
    val borderStyle: Style = Style.DEFAULT,
    val titleStyle: Style = Style.DEFAULT.bold(),
) {
    fun inner(area: Rect): Rect = Rect(
        area.x + 1,
        area.y + 1,
        (area.width - 2).coerceAtLeast(0),
        (area.height - 2).coerceAtLeast(0),
    )

    fun render(buf: Buffer, area: Rect) {
        if (area.isEmpty) return

        // Corners
        buf[area.left, area.top].apply { symbol = "╭"; style = borderStyle }
        buf[area.right - 1, area.top].apply { symbol = "╮"; style = borderStyle }
        buf[area.left, area.bottom - 1].apply { symbol = "╰"; style = borderStyle }
        buf[area.right - 1, area.bottom - 1].apply { symbol = "╯"; style = borderStyle }

        // Top/bottom edges
        for (x in (area.left + 1) until (area.right - 1)) {
            buf[x, area.top].apply { symbol = "─"; style = borderStyle }
            buf[x, area.bottom - 1].apply { symbol = "─"; style = borderStyle }
        }

        // Side edges
        for (y in (area.top + 1) until (area.bottom - 1)) {
            buf[area.left, y].apply { symbol = "│"; style = borderStyle }
            buf[area.right - 1, y].apply { symbol = "│"; style = borderStyle }
        }

        // Title
        title?.let { t ->
            val maxLen = area.width - 4
            if (maxLen > 0) {
                val display = " ${t.take(maxLen)} "
                buf.setString(area.x + 1, area.y, display, titleStyle)
            }
        }
    }
}

/**
 * Renders a list of lines inside an area with optional scrolling.
 */
fun renderLines(buf: Buffer, area: Rect, lines: List<Pair<String, Style>>, offset: Int = 0) {
    for ((i, pair) in lines.drop(offset).withIndex()) {
        if (i >= area.height) break
        val (text, style) = pair
        buf.setString(area.x, area.y + i, text.take(area.width), style)
    }
}

/**
 * Renders a single-line status bar filling the area width.
 */
fun renderStatusBar(buf: Buffer, area: Rect, text: String, style: Style) {
    buf.fill(area, " ", style)
    buf.setString(area.x, area.y, text.take(area.width), style)
}
