# KMP TUI Core Infrastructure

How to build the core infrastructure for a Ratatui-style immediate-mode TUI in Kotlin Multiplatform.
Covers types, buffer, terminal backends, layout, and ANSI rendering.

## Architecture

```
commonMain/                         ← All UI logic lives here
├── core/     Rect, Cell, Buffer, Style, Color, Modifier
├── layout/   Layout, Constraint, Direction, Flex
├── text/     Span, Line, Text (styled text model)
├── widget/   Widget, StatefulWidget, Frame
├── widgets/  Block, Paragraph, List, Table, Gauge, ...
└── app/      Terminal, App, EventLoop (coroutine-based)

jvmMain/
└── JvmTerminalBackend (JLine 3)

nativeMain/  (appleMain / linuxMain)
└── PosixTerminalBackend (termios + ANSI)
```

## Gradle Setup

```kotlin
// settings.gradle.kts
include(":tui")

// tui/build.gradle.kts
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    macosArm64()
    macosX64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        jvmMain.dependencies {
            implementation("org.jline:jline:3.27.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
```

## Core Types (commonMain)

### Rect

```kotlin
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

    fun centered(w: Constraint, h: Constraint): Rect {
        val cw = w.resolve(width)
        val ch = h.resolve(height)
        return Rect(x + (width - cw) / 2, y + (height - ch) / 2, cw, ch)
    }

    /** Splits this Rect using a layout — Kotlin destructuring-friendly. */
    fun split(layout: Layout): List<Rect> = layout.split(this)

    companion object {
        val ZERO = Rect(0, 0, 0, 0)
    }
}

data class Margin(val left: Int = 0, val right: Int = 0, val top: Int = 0, val bottom: Int = 0) {
    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom

    companion object {
        fun uniform(v: Int) = Margin(v, v, v, v)
        fun symmetric(h: Int, v: Int) = Margin(h, h, v, v)
    }
}
```

### Style, Color, Modifier

```kotlin
data class Style(
    val fg: Color = Color.Reset,
    val bg: Color = Color.Reset,
    val modifiers: Int = 0,  // bitmask for zero-alloc
) {
    fun fg(c: Color) = copy(fg = c)
    fun bg(c: Color) = copy(bg = c)
    fun bold() = copy(modifiers = modifiers or Modifier.BOLD)
    fun dim() = copy(modifiers = modifiers or Modifier.DIM)
    fun italic() = copy(modifiers = modifiers or Modifier.ITALIC)
    fun underlined() = copy(modifiers = modifiers or Modifier.UNDERLINED)
    fun reversed() = copy(modifiers = modifiers or Modifier.REVERSED)
    fun crossedOut() = copy(modifiers = modifiers or Modifier.CROSSED_OUT)

    /** Merge: other's set values override this. */
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

// Using bitmask instead of Set<Modifier> — zero allocation in render loop.
object Modifier {
    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINED = 1 shl 3
    const val SLOW_BLINK = 1 shl 4
    const val RAPID_BLINK = 1 shl 5
    const val REVERSED = 1 shl 6
    const val HIDDEN = 1 shl 7
    const val CROSSED_OUT = 1 shl 8
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
    data object LightRed : Color
    data object LightGreen : Color
    data object LightYellow : Color
    data object LightBlue : Color
    data object LightMagenta : Color
    data object LightCyan : Color
    data class Rgb(val r: Int, val g: Int, val b: Int) : Color
    data class Indexed(val index: Int) : Color  // 0-255
}
```

### Cell and Buffer

```kotlin
class Cell(var symbol: String = " ", var style: Style = Style.DEFAULT) {
    /** Width in terminal columns (1 for ASCII, 2 for CJK/emoji). */
    val width: Int get() = unicodeWidth(symbol)

    fun reset() { symbol = " "; style = Style.DEFAULT }

    override fun equals(other: Any?): Boolean =
        other is Cell && symbol == other.symbol && style == other.style

    override fun hashCode(): Int = 31 * symbol.hashCode() + style.hashCode()
}

class Buffer private constructor(val width: Int, val height: Int, private val cells: Array<Cell>) {
    constructor(width: Int, height: Int) : this(width, height, Array(width * height) { Cell() })

    operator fun get(x: Int, y: Int): Cell {
        check(x in 0 until width && y in 0 until height) { "($x,$y) out of bounds ($width×$height)" }
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
        forEachCell(area) { _, _, cell -> cell.symbol = symbol; cell.style = style }
    }

    fun clear() { cells.forEach { it.reset() } }

    /** Yields (x, y, cell) pairs that differ from [prev]. */
    fun diff(prev: Buffer): Sequence<Triple<Int, Int, Cell>> = sequence {
        require(width == prev.width && height == prev.height)
        for (i in cells.indices) {
            if (cells[i] != prev.cells[i]) {
                yield(Triple(i % width, i / width, cells[i]))
            }
        }
    }

    fun snapshot(): Buffer = Buffer(width, height, Array(width * height) { i ->
        Cell(cells[i].symbol, cells[i].style)
    })

    private inline fun forEachCell(area: Rect, action: (Int, Int, Cell) -> Unit) {
        for (row in area.top until area.bottom.coerceAtMost(height)) {
            for (col in area.left until area.right.coerceAtMost(width)) {
                action(col, row, cells[row * width + col])
            }
        }
    }
}

/** Placeholder — real impl needs Unicode East Asian Width property. */
private fun unicodeWidth(s: String): Int = if (s.length > 1) 2 else 1
```

## Layout System

### Constraints

```kotlin
sealed interface Constraint {
    data class Length(val value: Int) : Constraint
    data class Min(val value: Int) : Constraint
    data class Max(val value: Int) : Constraint
    data class Percentage(val value: Int) : Constraint
    data class Ratio(val num: Int, val den: Int) : Constraint
    data class Fill(val weight: Int = 1) : Constraint

    fun resolve(available: Int): Int = when (this) {
        is Length -> value.coerceIn(0, available)
        is Min -> value.coerceIn(0, available)
        is Max -> value.coerceIn(0, available)
        is Percentage -> (available * value / 100).coerceIn(0, available)
        is Ratio -> if (den == 0) 0 else (available * num / den).coerceIn(0, available)
        is Fill -> available
    }
}

// Shorthand factories:
fun length(n: Int) = Constraint.Length(n)
fun min(n: Int) = Constraint.Min(n)
fun max(n: Int) = Constraint.Max(n)
fun percentage(n: Int) = Constraint.Percentage(n)
fun ratio(num: Int, den: Int) = Constraint.Ratio(num, den)
fun fill(weight: Int = 1) = Constraint.Fill(weight)
```

### Layout

```kotlin
enum class Direction { VERTICAL, HORIZONTAL }

class Layout(
    val direction: Direction,
    val constraints: List<Constraint>,
    val margin: Margin = Margin(),
) {
    fun split(area: Rect): List<Rect> {
        val inner = area.inner(margin)
        val available = when (direction) {
            Direction.VERTICAL -> inner.height
            Direction.HORIZONTAL -> inner.width
        }
        val sizes = solve(constraints, available)
        return buildList {
            var offset = 0
            for (size in sizes) {
                add(when (direction) {
                    Direction.VERTICAL -> Rect(inner.x, inner.y + offset, inner.width, size)
                    Direction.HORIZONTAL -> Rect(inner.x + offset, inner.y, size, inner.height)
                })
                offset += size
            }
        }
    }

    companion object {
        fun vertical(vararg c: Constraint) = Layout(Direction.VERTICAL, c.toList())
        fun horizontal(vararg c: Constraint) = Layout(Direction.HORIZONTAL, c.toList())
    }
}

private fun solve(constraints: List<Constraint>, available: Int): List<Int> {
    val sizes = IntArray(constraints.size)
    var remaining = available
    var fillWeight = 0

    // Pass 1: fixed allocations
    for ((i, c) in constraints.withIndex()) {
        when (c) {
            is Constraint.Fill -> fillWeight += c.weight
            else -> { sizes[i] = c.resolve(available).coerceAtMost(remaining); remaining -= sizes[i] }
        }
    }
    // Pass 2: distribute remaining to Fill
    if (fillWeight > 0 && remaining > 0) {
        var distributed = 0
        for ((i, c) in constraints.withIndex()) {
            if (c is Constraint.Fill) {
                sizes[i] = remaining * c.weight / fillWeight
                distributed += sizes[i]
            }
        }
        // Rounding remainder goes to last Fill
        val rounding = remaining - distributed
        if (rounding > 0) {
            val lastFill = constraints.indexOfLast { it is Constraint.Fill }
            if (lastFill >= 0) sizes[lastFill] += rounding
        }
    }
    // Pass 3: enforce Min/Max post-fill
    for ((i, c) in constraints.withIndex()) {
        when (c) {
            is Constraint.Min -> sizes[i] = sizes[i].coerceAtLeast(c.value).coerceAtMost(available)
            is Constraint.Max -> sizes[i] = sizes[i].coerceAtMost(c.value)
            else -> {}
        }
    }
    return sizes.toList()
}

/** Destructuring support for layout results. */
operator fun List<Rect>.component1() = this[0]
operator fun List<Rect>.component2() = this[1]
operator fun List<Rect>.component3() = this[2]
operator fun List<Rect>.component4() = this[3]
operator fun List<Rect>.component5() = this[4]
```

## Terminal Backend (expect/actual)

### Common Interface

```kotlin
// commonMain
interface TerminalBackend {
    fun size(): TerminalSize
    fun enterRawMode()
    fun exitRawMode()
    fun enterAlternateScreen()
    fun exitAlternateScreen()
    fun hideCursor()
    fun showCursor()
    fun moveCursor(x: Int, y: Int)
    fun setStyle(style: Style)
    fun resetStyle()
    fun print(text: String)
    fun clear()
    fun flush()

    /** Non-blocking event read with timeout. Returns null on timeout. */
    fun pollEvent(timeoutMillis: Long): TerminalEvent?
}

data class TerminalSize(val columns: Int, val rows: Int)
```

### Events

```kotlin
sealed interface TerminalEvent {
    data class Key(val code: KeyCode, val modifiers: KeyModifiers = KeyModifiers.NONE) : TerminalEvent
    data class Resize(val columns: Int, val rows: Int) : TerminalEvent
    data class Mouse(val kind: MouseKind, val x: Int, val y: Int, val modifiers: KeyModifiers = KeyModifiers.NONE) : TerminalEvent
    data object FocusGained : TerminalEvent
    data object FocusLost : TerminalEvent
}

sealed interface KeyCode {
    data class Char(val c: kotlin.Char) : KeyCode
    data object Enter : KeyCode
    data object Escape : KeyCode
    data object Backspace : KeyCode
    data object Tab : KeyCode
    data object BackTab : KeyCode  // Shift+Tab
    data object Up : KeyCode
    data object Down : KeyCode
    data object Left : KeyCode
    data object Right : KeyCode
    data object Home : KeyCode
    data object End : KeyCode
    data object PageUp : KeyCode
    data object PageDown : KeyCode
    data object Insert : KeyCode
    data object Delete : KeyCode
    data class F(val n: Int) : KeyCode  // F1-F12
}

@JvmInline
value class KeyModifiers(val bits: Int) {
    operator fun contains(mod: Int): Boolean = (bits and mod) != 0
    operator fun plus(mod: Int) = KeyModifiers(bits or mod)

    companion object {
        val NONE = KeyModifiers(0)
        const val SHIFT = 1
        const val CONTROL = 2
        const val ALT = 4
    }
}

enum class MouseKind { DOWN, UP, DRAG, MOVED, SCROLL_UP, SCROLL_DOWN }
```

### JVM Backend (jvmMain)

```kotlin
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Terminal as JTerminal
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingReader

class JvmTerminalBackend : TerminalBackend {
    private val terminal: JTerminal = TerminalBuilder.builder().system(true).build()
    private val reader: NonBlockingReader = terminal.reader()
    private val writer get() = terminal.writer()

    override fun size(): TerminalSize = terminal.size.let { TerminalSize(it.columns, it.rows) }
    override fun enterRawMode() { terminal.enterRawMode() }
    override fun exitRawMode() { /* JLine restores on close */ }
    override fun enterAlternateScreen() { esc("?1049h") }
    override fun exitAlternateScreen() { esc("?1049l") }
    override fun hideCursor() { esc("?25l") }
    override fun showCursor() { esc("?25h") }
    override fun moveCursor(x: Int, y: Int) { esc("${y + 1};${x + 1}H") }
    override fun resetStyle() { esc("0m") }
    override fun clear() { esc("2J"); esc("1;1H") }
    override fun print(text: String) { writer.write(text) }
    override fun flush() { writer.flush() }

    override fun setStyle(style: Style) { writer.write(style.toAnsiSequence()) }

    override fun pollEvent(timeoutMillis: Long): TerminalEvent? {
        val first = reader.read(timeoutMillis)
        if (first == -1) return null
        return parseEscapeSequence(first)
    }

    private fun parseEscapeSequence(first: Int): TerminalEvent {
        if (first != 27) return keyFromByte(first)
        val second = reader.read(50)  // short timeout for escape sequences
        if (second == -1) return TerminalEvent.Key(KeyCode.Escape)
        if (second != '['.code) return TerminalEvent.Key(KeyCode.Char(second.toChar()), KeyModifiers(KeyModifiers.ALT))

        // CSI sequence: ESC [ ...
        val params = buildString {
            while (true) {
                val b = reader.read(50)
                if (b == -1) break
                if (b.toChar() in '0'..'9' || b.toChar() == ';') append(b.toChar())
                else { return parseCsiTerminator(b.toChar(), this.toString()) }
            }
        }
        return TerminalEvent.Key(KeyCode.Escape)
    }

    private fun parseCsiTerminator(terminator: Char, params: String): TerminalEvent {
        val modifiers = if (';' in params) {
            val mod = params.substringAfter(';').toIntOrNull()?.minus(1) ?: 0
            KeyModifiers(mod)
        } else KeyModifiers.NONE

        val code: KeyCode = when (terminator) {
            'A' -> KeyCode.Up
            'B' -> KeyCode.Down
            'C' -> KeyCode.Right
            'D' -> KeyCode.Left
            'H' -> KeyCode.Home
            'F' -> KeyCode.End
            '~' -> when (params.substringBefore(';').toIntOrNull()) {
                1 -> KeyCode.Home; 2 -> KeyCode.Insert; 3 -> KeyCode.Delete
                4 -> KeyCode.End; 5 -> KeyCode.PageUp; 6 -> KeyCode.PageDown
                15 -> KeyCode.F(5); 17 -> KeyCode.F(6); 18 -> KeyCode.F(7)
                19 -> KeyCode.F(8); 20 -> KeyCode.F(9); 21 -> KeyCode.F(10)
                23 -> KeyCode.F(11); 24 -> KeyCode.F(12)
                else -> KeyCode.Escape
            }
            'P' -> KeyCode.F(1); 'Q' -> KeyCode.F(2); 'R' -> KeyCode.F(3); 'S' -> KeyCode.F(4)
            'Z' -> KeyCode.BackTab
            else -> KeyCode.Escape
        }
        return TerminalEvent.Key(code, modifiers)
    }

    private fun keyFromByte(byte: Int): TerminalEvent {
        val code = when (byte) {
            9 -> KeyCode.Tab
            10, 13 -> KeyCode.Enter
            27 -> KeyCode.Escape
            127 -> KeyCode.Backspace
            in 1..26 -> return TerminalEvent.Key(
                KeyCode.Char(('a' + byte - 1)),
                KeyModifiers(KeyModifiers.CONTROL)
            )
            else -> KeyCode.Char(byte.toChar())
        }
        return TerminalEvent.Key(code)
    }

    private fun esc(code: String) { writer.write("\u001b[$code") }

    fun close() { terminal.close() }
}
```

### Native Backend (nativeMain — POSIX)

```kotlin
import kotlinx.cinterop.*
import platform.posix.*

class PosixTerminalBackend : TerminalBackend {
    private val savedTermios = nativeHeap.alloc<termios>()
    private var rawMode = false

    override fun size(): TerminalSize = memScoped {
        val ws = alloc<winsize>()
        ioctl(STDOUT_FILENO, TIOCGWINSZ.toULong(), ws.ptr)
        TerminalSize(ws.ws_col.toInt(), ws.ws_row.toInt())
    }

    override fun enterRawMode() {
        tcgetattr(STDIN_FILENO, savedTermios.ptr)
        memScoped {
            val raw = alloc<termios>()
            tcgetattr(STDIN_FILENO, raw.ptr)
            raw.c_lflag = raw.c_lflag and (ICANON or ECHO or ISIG or IEXTEN).inv().toUInt()
            raw.c_iflag = raw.c_iflag and (IXON or ICRNL or BRKINT or INPCK or ISTRIP).inv().toUInt()
            raw.c_oflag = raw.c_oflag and OPOST.inv().toUInt()
            raw.c_cflag = raw.c_cflag or CS8.toUInt()
            raw.c_cc[VMIN] = 0u
            raw.c_cc[VTIME] = 0u
            tcsetattr(STDIN_FILENO, TCSAFLUSH, raw.ptr)
        }
        rawMode = true
    }

    override fun exitRawMode() {
        if (rawMode) { tcsetattr(STDIN_FILENO, TCSAFLUSH, savedTermios.ptr); rawMode = false }
    }

    override fun enterAlternateScreen() { writeStdout("\u001b[?1049h") }
    override fun exitAlternateScreen() { writeStdout("\u001b[?1049l") }
    override fun hideCursor() { writeStdout("\u001b[?25l") }
    override fun showCursor() { writeStdout("\u001b[?25h") }
    override fun moveCursor(x: Int, y: Int) { writeStdout("\u001b[${y + 1};${x + 1}H") }
    override fun setStyle(style: Style) { writeStdout(style.toAnsiSequence()) }
    override fun resetStyle() { writeStdout("\u001b[0m") }
    override fun print(text: String) { writeStdout(text) }
    override fun clear() { writeStdout("\u001b[2J\u001b[1;1H") }
    override fun flush() { /* stdout is unbuffered after OPOST is off */ }

    override fun pollEvent(timeoutMillis: Long): TerminalEvent? = memScoped {
        val fds = alloc<pollfd>()
        fds.fd = STDIN_FILENO
        fds.events = POLLIN.toShort()
        val ready = poll(fds.ptr, 1u, timeoutMillis.toInt())
        if (ready <= 0) return null

        val buf = ByteArray(16)
        val n = buf.usePinned { pinned ->
            read(STDIN_FILENO, pinned.addressOf(0), buf.size.toULong()).toInt()
        }
        if (n <= 0) return null
        parseBytes(buf, n)
    }

    private fun parseBytes(buf: ByteArray, len: Int): TerminalEvent {
        if (len == 1) return when (val b = buf[0].toInt()) {
            9 -> TerminalEvent.Key(KeyCode.Tab)
            10, 13 -> TerminalEvent.Key(KeyCode.Enter)
            27 -> TerminalEvent.Key(KeyCode.Escape)
            127 -> TerminalEvent.Key(KeyCode.Backspace)
            in 1..26 -> TerminalEvent.Key(KeyCode.Char(('a' + b - 1)), KeyModifiers(KeyModifiers.CONTROL))
            else -> TerminalEvent.Key(KeyCode.Char(b.toChar()))
        }
        // ESC [ sequence
        if (len >= 3 && buf[0].toInt() == 27 && buf[1].toInt() == '['.code) {
            val terminator = buf[len - 1].toInt().toChar()
            return when (terminator) {
                'A' -> TerminalEvent.Key(KeyCode.Up)
                'B' -> TerminalEvent.Key(KeyCode.Down)
                'C' -> TerminalEvent.Key(KeyCode.Right)
                'D' -> TerminalEvent.Key(KeyCode.Left)
                'H' -> TerminalEvent.Key(KeyCode.Home)
                'F' -> TerminalEvent.Key(KeyCode.End)
                'Z' -> TerminalEvent.Key(KeyCode.BackTab)
                '~' -> {
                    val param = buf.decodeToString(2, len - 1).substringBefore(';').toIntOrNull()
                    when (param) {
                        2 -> TerminalEvent.Key(KeyCode.Insert)
                        3 -> TerminalEvent.Key(KeyCode.Delete)
                        5 -> TerminalEvent.Key(KeyCode.PageUp)
                        6 -> TerminalEvent.Key(KeyCode.PageDown)
                        else -> TerminalEvent.Key(KeyCode.Escape)
                    }
                }
                else -> TerminalEvent.Key(KeyCode.Escape)
            }
        }
        return TerminalEvent.Key(KeyCode.Escape)
    }

    private fun writeStdout(s: String) {
        val bytes = s.encodeToByteArray()
        bytes.usePinned { pinned ->
            write(STDOUT_FILENO, pinned.addressOf(0), bytes.size.toULong())
        }
    }

    fun close() { exitRawMode(); nativeHeap.free(savedTermios) }
}
```

## ANSI Rendering

```kotlin
fun Style.toAnsiSequence(): String = buildString {
    append("\u001b[0")
    appendFgCode(fg)
    appendBgCode(bg)
    if (modifiers != 0) {
        if (hasModifier(Modifier.BOLD)) append(";1")
        if (hasModifier(Modifier.DIM)) append(";2")
        if (hasModifier(Modifier.ITALIC)) append(";3")
        if (hasModifier(Modifier.UNDERLINED)) append(";4")
        if (hasModifier(Modifier.SLOW_BLINK)) append(";5")
        if (hasModifier(Modifier.RAPID_BLINK)) append(";6")
        if (hasModifier(Modifier.REVERSED)) append(";7")
        if (hasModifier(Modifier.HIDDEN)) append(";8")
        if (hasModifier(Modifier.CROSSED_OUT)) append(";9")
    }
    append('m')
}

private fun StringBuilder.appendFgCode(c: Color) = when (c) {
    Color.Reset -> {}
    Color.Black -> append(";30"); Color.Red -> append(";31")
    Color.Green -> append(";32"); Color.Yellow -> append(";33")
    Color.Blue -> append(";34"); Color.Magenta -> append(";35")
    Color.Cyan -> append(";36"); Color.White -> append(";37")
    Color.DarkGray -> append(";90"); Color.LightRed -> append(";91")
    Color.LightGreen -> append(";92"); Color.LightYellow -> append(";93")
    Color.LightBlue -> append(";94"); Color.LightMagenta -> append(";95")
    Color.LightCyan -> append(";96")
    is Color.Rgb -> append(";38;2;${c.r};${c.g};${c.b}")
    is Color.Indexed -> append(";38;5;${c.index}")
}

private fun StringBuilder.appendBgCode(c: Color) = when (c) {
    Color.Reset -> {}
    Color.Black -> append(";40"); Color.Red -> append(";41")
    Color.Green -> append(";42"); Color.Yellow -> append(";43")
    Color.Blue -> append(";44"); Color.Magenta -> append(";45")
    Color.Cyan -> append(";46"); Color.White -> append(";47")
    Color.DarkGray -> append(";100"); Color.LightRed -> append(";101")
    Color.LightGreen -> append(";102"); Color.LightYellow -> append(";103")
    Color.LightBlue -> append(";104"); Color.LightMagenta -> append(";105")
    Color.LightCyan -> append(";106")
    is Color.Rgb -> append(";48;2;${c.r};${c.g};${c.b}")
    is Color.Indexed -> append(";48;5;${c.index}")
}
```

## Terminal (Double-Buffered)

```kotlin
class Terminal(private val backend: TerminalBackend) {
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
        backend.exitRawMode()
        backend.flush()
    }

    fun draw(render: (Frame) -> Unit) {
        val size = backend.size()
        val buffer = Buffer(size.columns, size.rows)
        val frame = Frame(buffer, Rect(0, 0, size.columns, size.rows))

        render(frame)

        // Diff-flush: only send changed cells
        val prev = previousBuffer
        if (prev == null || prev.width != size.columns || prev.height != size.rows) {
            // Full repaint on first draw or resize
            backend.clear()
            renderFullBuffer(buffer)
        } else {
            renderDiff(buffer, prev)
        }

        frame.getCursorPosition()?.let { (x, y) ->
            backend.moveCursor(x, y)
            backend.showCursor()
        }
        backend.flush()
        previousBuffer = buffer.snapshot()
    }

    /** Convenience: init, run block, restore (even on exception/panic). */
    inline fun <R> run(block: Terminal.() -> R): R {
        init()
        return try { block() } finally { restore() }
    }

    private fun renderFullBuffer(buf: Buffer) {
        var lastStyle = Style.DEFAULT
        for (y in 0 until buf.height) {
            backend.moveCursor(0, y)
            for (x in 0 until buf.width) {
                val cell = buf[x, y]
                if (cell.style != lastStyle) { backend.setStyle(cell.style); lastStyle = cell.style }
                backend.print(cell.symbol)
            }
        }
        backend.resetStyle()
    }

    private fun renderDiff(current: Buffer, prev: Buffer) {
        var lastStyle: Style? = null
        for ((x, y, cell) in current.diff(prev)) {
            backend.moveCursor(x, y)
            if (cell.style != lastStyle) { backend.setStyle(cell.style); lastStyle = cell.style }
            backend.print(cell.symbol)
        }
        if (lastStyle != null) backend.resetStyle()
    }
}
```

## TestBackend (commonTest)

```kotlin
/** In-memory backend for unit testing widgets without a real terminal. */
class TestBackend(columns: Int, rows: Int) : TerminalBackend {
    val buffer = Buffer(columns, rows)
    val events = ArrayDeque<TerminalEvent>()
    private val _size = TerminalSize(columns, rows)
    var cursorVisible = true; private set
    var cursorX = 0; private set
    var cursorY = 0; private set

    override fun size() = _size
    override fun enterRawMode() {}
    override fun exitRawMode() {}
    override fun enterAlternateScreen() {}
    override fun exitAlternateScreen() {}
    override fun hideCursor() { cursorVisible = false }
    override fun showCursor() { cursorVisible = true }
    override fun moveCursor(x: Int, y: Int) { cursorX = x; cursorY = y }
    override fun setStyle(style: Style) {}
    override fun resetStyle() {}
    override fun print(text: String) {}
    override fun clear() { buffer.clear() }
    override fun flush() {}
    override fun pollEvent(timeoutMillis: Long): TerminalEvent? = events.removeFirstOrNull()

    /** Push a key event to be consumed by pollEvent. */
    fun pushKey(code: KeyCode, modifiers: KeyModifiers = KeyModifiers.NONE) {
        events.addLast(TerminalEvent.Key(code, modifiers))
    }

    /** Assert that a specific row contains expected text. */
    fun assertRow(y: Int, expected: String) {
        val actual = buildString {
            for (x in 0 until _size.columns) append(buffer[x, y].symbol)
        }.trimEnd()
        check(actual.startsWith(expected.trimEnd())) {
            "Row $y: expected \"$expected\" but got \"$actual\""
        }
    }
}
```

## Key Differences: Ratatui (Rust) → KMP

| Aspect | Ratatui | KMP |
|--------|---------|-----|
| Widget ownership | `self` consumed on render | No move semantics; widgets reusable |
| Backend dispatch | Generic `Terminal<B: Backend>` | Interface `TerminalBackend` (or expect/actual) |
| Modifier set | `Modifier::BOLD \| Modifier::ITALIC` | Bitmask Int (zero-alloc) |
| Cell content | `&str` (multi-codepoint grapheme) | `String` (symbol field) |
| Layout result | Fixed-size array `[Rect; N]` | `List<Rect>` + componentN destructuring |
| Event loop | `crossterm::event::read()` | `backend.pollEvent()` or coroutine Flow |
| Error handling | `Result<T, io::Error>` | Exceptions or `runCatching {}` |
| Buffer diff | Hidden internal | Explicit `buffer.diff(prev)` as Sequence |
| Alloc in loop | Zero-cost (stack) | Minimize via bitmask Style, reuse Buffer |
