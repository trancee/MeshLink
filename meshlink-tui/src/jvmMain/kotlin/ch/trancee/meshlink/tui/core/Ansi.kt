package ch.trancee.meshlink.tui.core

/**
 * ANSI escape sequence generation for [Style].
 */
fun Style.toAnsiSequence(): String = buildString {
    append("\u001b[0")
    appendFgCode(fg)
    appendBgCode(bg)
    if (modifiers != 0) {
        if (hasModifier(Modifier.BOLD)) append(";1")
        if (hasModifier(Modifier.DIM)) append(";2")
        if (hasModifier(Modifier.ITALIC)) append(";3")
        if (hasModifier(Modifier.UNDERLINED)) append(";4")
        if (hasModifier(Modifier.REVERSED)) append(";7")
    }
    append('m')
}

private fun StringBuilder.appendFgCode(c: Color) {
    when (c) {
        Color.Reset -> {}
        Color.Black -> append(";30")
        Color.Red -> append(";31")
        Color.Green -> append(";32")
        Color.Yellow -> append(";33")
        Color.Blue -> append(";34")
        Color.Magenta -> append(";35")
        Color.Cyan -> append(";36")
        Color.White -> append(";37")
        Color.DarkGray -> append(";90")
        Color.LightGreen -> append(";92")
        Color.LightYellow -> append(";93")
        Color.LightBlue -> append(";94")
        Color.LightCyan -> append(";96")
        is Color.Rgb -> append(";38;2;${c.r};${c.g};${c.b}")
    }
}

private fun StringBuilder.appendBgCode(c: Color) {
    when (c) {
        Color.Reset -> {}
        Color.Black -> append(";40")
        Color.Red -> append(";41")
        Color.Green -> append(";42")
        Color.Yellow -> append(";43")
        Color.Blue -> append(";44")
        Color.Magenta -> append(";45")
        Color.Cyan -> append(";46")
        Color.White -> append(";47")
        Color.DarkGray -> append(";100")
        Color.LightGreen -> append(";102")
        Color.LightYellow -> append(";103")
        Color.LightBlue -> append(";104")
        Color.LightCyan -> append(";106")
        is Color.Rgb -> append(";48;2;${c.r};${c.g};${c.b}")
    }
}
