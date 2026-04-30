package ch.trancee.meshlink.tui.core

/**
 * Terminal cell style using ANSI colors and bitmask modifiers.
 */
data class Style(
    val fg: Color = Color.Reset,
    val bg: Color = Color.Reset,
    val modifiers: Int = 0,
) {
    fun fg(c: Color) = copy(fg = c)
    fun bg(c: Color) = copy(bg = c)
    fun bold() = copy(modifiers = modifiers or Modifier.BOLD)
    fun dim() = copy(modifiers = modifiers or Modifier.DIM)
    fun italic() = copy(modifiers = modifiers or Modifier.ITALIC)
    fun reversed() = copy(modifiers = modifiers or Modifier.REVERSED)

    fun patch(other: Style) = Style(
        fg = if (other.fg != Color.Reset) other.fg else fg,
        bg = if (other.bg != Color.Reset) other.bg else bg,
        modifiers = modifiers or other.modifiers,
    )

    fun hasModifier(mod: Int): Boolean = (modifiers and mod) != 0

    companion object {
        val DEFAULT = Style()
    }
}

object Modifier {
    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINED = 1 shl 3
    const val REVERSED = 1 shl 6
}

sealed interface Color {
    data object Reset : Color
    data object Black : Color
    data object Red : Color
    data object Green : Color
    data object Yellow : Color
    data object Blue : Color
    data object Magenta : Color
    data object Cyan : Color
    data object White : Color
    data object DarkGray : Color
    data object LightGreen : Color
    data object LightYellow : Color
    data object LightBlue : Color
    data object LightCyan : Color
    data class Rgb(val r: Int, val g: Int, val b: Int) : Color
}
