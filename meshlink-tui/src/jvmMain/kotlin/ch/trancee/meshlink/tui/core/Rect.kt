package ch.trancee.meshlink.tui.core

/**
 * Rectangle in terminal space.
 */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val left: Int get() = x
    val right: Int get() = x + width
    val top: Int get() = y
    val bottom: Int get() = y + height
    val area: Int get() = width * height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun inner(margin: Margin): Rect = Rect(
        x = x + margin.left,
        y = y + margin.top,
        width = (width - margin.horizontal).coerceAtLeast(0),
        height = (height - margin.vertical).coerceAtLeast(0),
    )

    companion object {
        val ZERO = Rect(0, 0, 0, 0)
    }
}

data class Margin(val left: Int = 0, val right: Int = 0, val top: Int = 0, val bottom: Int = 0) {
    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom

    companion object {
        fun uniform(v: Int) = Margin(v, v, v, v)
    }
}
