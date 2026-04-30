# KMP TUI Widgets and Application Patterns

Practical widgets, DSL builders, styled text model, coroutine-based event loop,
and application architecture for a Ratatui-style KMP TUI.

Depends on types from `kmp-core.md` (Rect, Buffer, Style, Color, Widget, Frame, Terminal, etc.)

## Styled Text Model

Ratatui's `Span → Line → Text` hierarchy mapped to Kotlin:

```kotlin
/** A styled fragment of text (inline). */
data class Span(val content: String, val style: Style = Style.DEFAULT) {
    val width: Int get() = content.length  // simplified; real impl counts grapheme widths
}

/** A horizontal sequence of Spans (one terminal row). */
data class Line(val spans: List<Span>) {
    constructor(content: String, style: Style = Style.DEFAULT) : this(listOf(Span(content, style)))

    val width: Int get() = spans.sumOf { it.width }

    fun render(buf: Buffer, x: Int, y: Int, maxWidth: Int) {
        var col = x
        for (span in spans) {
            for (ch in span.content) {
                if (col - x >= maxWidth) return
                buf[col, y].symbol = ch.toString()
                buf[col, y].style = span.style
                col++
            }
        }
    }
}

/** Multiline text (vertical sequence of Lines). */
data class Text(val lines: List<Line>) {
    constructor(content: String, style: Style = Style.DEFAULT) : this(
        content.lines().map { Line(it, style) }
    )

    val width: Int get() = lines.maxOfOrNull { it.width } ?: 0
    val height: Int get() = lines.size
}

// DSL builders for styled text:
fun span(content: String, style: Style = Style.DEFAULT) = Span(content, style)
fun line(vararg spans: Span) = Line(spans.toList())
fun line(content: String, style: Style = Style.DEFAULT) = Line(content, style)
fun text(vararg lines: Line) = Text(lines.toList())
fun text(content: String, style: Style = Style.DEFAULT) = Text(content, style)

// Fluent style extensions:
fun String.styled(style: Style) = Span(this, style)
fun String.bold() = Span(this, Style.DEFAULT.bold())
fun String.red() = Span(this, Style.DEFAULT.fg(Color.Red))
fun String.green() = Span(this, Style.DEFAULT.fg(Color.Green))
fun String.yellow() = Span(this, Style.DEFAULT.fg(Color.Yellow))
fun String.blue() = Span(this, Style.DEFAULT.fg(Color.Blue))
fun String.cyan() = Span(this, Style.DEFAULT.fg(Color.Cyan))
fun String.dim() = Span(this, Style.DEFAULT.dim())
fun String.italic() = Span(this, Style.DEFAULT.italic())
fun String.fg(c: Color) = Span(this, Style.DEFAULT.fg(c))
```

## Widget DSL

Kotlin type-safe builder pattern for composing widgets declaratively:

```kotlin
/** DSL scope for building the UI tree inside terminal.draw { }. */
class FrameScope(val frame: Frame) {
    val area: Rect get() = frame.area

    fun widget(w: Widget, area: Rect) = frame.renderWidget(w, area)
    fun <S> statefulWidget(w: StatefulWidget<S>, area: Rect, state: S) = frame.renderStatefulWidget(w, area, state)

    /** Layout helper — splits area and yields sub-areas. */
    inline fun vertical(area: Rect, vararg constraints: Constraint, block: (List<Rect>) -> Unit) {
        block(Layout.vertical(*constraints).split(area))
    }

    inline fun horizontal(area: Rect, vararg constraints: Constraint, block: (List<Rect>) -> Unit) {
        block(Layout.horizontal(*constraints).split(area))
    }
}

// Usage:
terminal.draw { frame ->
    val scope = FrameScope(frame)
    with(scope) {
        val (header, body, footer) = Layout.vertical(length(3), fill(), length(1)).split(area)
        widget(BlockWidget(title = "Header", borders = Border.ALL), header)
        widget(paragraph, body)
        widget(statusBar, footer)
    }
}
```

## Built-in Widgets

### Block

```kotlin
class BlockWidget(
    val title: Line? = null,
    val borders: Set<Border> = Border.ALL,
    val borderChars: BorderChars = BorderChars.ROUNDED,
    val borderStyle: Style = Style.DEFAULT,
    val style: Style = Style.DEFAULT,
    val padding: Margin = Margin(),
) : Widget {

    fun inner(area: Rect): Rect {
        val bTop = if (Border.TOP in borders) 1 else 0
        val bBottom = if (Border.BOTTOM in borders) 1 else 0
        val bLeft = if (Border.LEFT in borders) 1 else 0
        val bRight = if (Border.RIGHT in borders) 1 else 0
        return Rect(
            area.x + bLeft + padding.left,
            area.y + bTop + padding.top,
            (area.width - bLeft - bRight - padding.horizontal).coerceAtLeast(0),
            (area.height - bTop - bBottom - padding.vertical).coerceAtLeast(0),
        )
    }

    override fun render(area: Rect, buf: Buffer) {
        if (area.isEmpty) return
        buf.setStyle(area, style)
        // Top border
        if (Border.TOP in borders && area.height > 0) {
            for (x in area.left until area.right) buf[x, area.top].apply { symbol = borderChars.horizontal; style = borderStyle }
            if (Border.LEFT in borders) buf[area.left, area.top].symbol = borderChars.topLeft
            if (Border.RIGHT in borders) buf[area.right - 1, area.top].symbol = borderChars.topRight
        }
        // Bottom border
        if (Border.BOTTOM in borders && area.height > 1) {
            for (x in area.left until area.right) buf[x, area.bottom - 1].apply { symbol = borderChars.horizontal; style = borderStyle }
            if (Border.LEFT in borders) buf[area.left, area.bottom - 1].symbol = borderChars.bottomLeft
            if (Border.RIGHT in borders) buf[area.right - 1, area.bottom - 1].symbol = borderChars.bottomRight
        }
        // Side borders
        val yStart = area.top + (if (Border.TOP in borders) 1 else 0)
        val yEnd = area.bottom - (if (Border.BOTTOM in borders) 1 else 0)
        for (y in yStart until yEnd) {
            if (Border.LEFT in borders) buf[area.left, y].apply { symbol = borderChars.vertical; style = borderStyle }
            if (Border.RIGHT in borders) buf[area.right - 1, y].apply { symbol = borderChars.vertical; style = borderStyle }
        }
        // Title
        title?.render(buf, area.x + 1, area.y, area.width - 2)
    }

    companion object {
        fun bordered(title: String? = null) = BlockWidget(title = title?.let { Line(it) })
        fun bordered(title: Line) = BlockWidget(title = title)
    }
}

enum class Border { TOP, BOTTOM, LEFT, RIGHT;
    companion object { val ALL = setOf(TOP, BOTTOM, LEFT, RIGHT); val NONE = emptySet<Border>() }
}

data class BorderChars(
    val topLeft: String, val topRight: String,
    val bottomLeft: String, val bottomRight: String,
    val horizontal: String, val vertical: String,
) {
    companion object {
        val PLAIN = BorderChars("┌", "┐", "└", "┘", "─", "│")
        val ROUNDED = BorderChars("╭", "╮", "╰", "╯", "─", "│")
        val DOUBLE = BorderChars("╔", "╗", "╚", "╝", "═", "║")
        val THICK = BorderChars("┏", "┓", "┗", "┛", "━", "┃")
    }
}
```

### Paragraph

```kotlin
class Paragraph(
    val text: Text,
    val block: BlockWidget? = null,
    val style: Style = Style.DEFAULT,
    val alignment: Alignment = Alignment.LEFT,
    val wrap: Boolean = false,
    val scroll: Pair<Int, Int> = 0 to 0,  // (vertical, horizontal)
) : Widget {

    constructor(content: String, style: Style = Style.DEFAULT, block: BlockWidget? = null)
        : this(Text(content, style), block, style)

    override fun render(area: Rect, buf: Buffer) {
        buf.setStyle(area, style)
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty) return

        val lines = if (wrap) wrapLines(text.lines, inner.width) else text.lines
        val (scrollY, scrollX) = scroll

        for ((i, line) in lines.drop(scrollY).withIndex()) {
            if (i >= inner.height) break
            val x = when (alignment) {
                Alignment.LEFT -> inner.x
                Alignment.CENTER -> inner.x + (inner.width - line.width) / 2
                Alignment.RIGHT -> inner.x + inner.width - line.width
            }
            line.render(buf, x - scrollX, inner.y + i, inner.width)
        }
    }

    private fun wrapLines(lines: List<Line>, maxWidth: Int): List<Line> = buildList {
        for (line in lines) {
            if (line.width <= maxWidth) { add(line); continue }
            // Simple wrapping at maxWidth (word-break-aware wrapping is more complex)
            var remaining = line.spans.flatMap { span ->
                span.content.chunked(maxWidth).map { Span(it, span.style) }
            }
            remaining.forEach { add(Line(listOf(it))) }
        }
    }
}

enum class Alignment { LEFT, CENTER, RIGHT }
```

### List (Stateful)

```kotlin
class ListWidget(
    val items: List<ListItem>,
    val block: BlockWidget? = null,
    val style: Style = Style.DEFAULT,
    val highlightStyle: Style = Style.DEFAULT.reversed(),
    val highlightSymbol: String = "▶ ",
) : StatefulWidget<ListState> {

    override fun render(area: Rect, buf: Buffer, state: ListState) {
        buf.setStyle(area, style)
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty || items.isEmpty()) return

        // Adjust scroll to keep selection visible
        state.adjustScroll(inner.height, items.size)

        val symbolWidth = highlightSymbol.length
        for ((i, item) in items.drop(state.offset).withIndex()) {
            if (i >= inner.height) break
            val y = inner.y + i
            val itemIndex = i + state.offset
            val isSelected = state.selected == itemIndex

            if (isSelected) {
                buf.setString(inner.x, y, highlightSymbol, highlightStyle)
                buf.setStyle(Rect(inner.x, y, inner.width, 1), highlightStyle)
            }

            val contentX = inner.x + if (isSelected) symbolWidth else symbolWidth // reserve space
            val maxWidth = inner.width - symbolWidth
            item.content.render(buf, contentX, y, maxWidth)
            if (isSelected) {
                // Apply highlight over item content
                for (x in inner.x until (inner.x + inner.width).coerceAtMost(buf.width)) {
                    buf[x, y].style = buf[x, y].style.patch(highlightStyle)
                }
            }
        }
    }
}

data class ListItem(val content: Line, val style: Style = Style.DEFAULT) {
    constructor(text: String, style: Style = Style.DEFAULT) : this(Line(text, style), style)
}

class ListState(
    var selected: Int = 0,
    var offset: Int = 0,
) {
    fun selectNext(itemCount: Int) { selected = (selected + 1).coerceAtMost(itemCount - 1) }
    fun selectPrevious() { selected = (selected - 1).coerceAtLeast(0) }
    fun selectFirst() { selected = 0 }
    fun selectLast(itemCount: Int) { selected = (itemCount - 1).coerceAtLeast(0) }

    internal fun adjustScroll(visibleHeight: Int, itemCount: Int) {
        if (selected < offset) offset = selected
        if (selected >= offset + visibleHeight) offset = selected - visibleHeight + 1
        offset = offset.coerceIn(0, (itemCount - visibleHeight).coerceAtLeast(0))
    }
}
```

### Table (Stateful)

```kotlin
class TableWidget(
    val rows: List<TableRow>,
    val header: TableRow? = null,
    val columnWidths: List<Constraint>,
    val block: BlockWidget? = null,
    val style: Style = Style.DEFAULT,
    val headerStyle: Style = Style.DEFAULT.bold(),
    val highlightStyle: Style = Style.DEFAULT.reversed(),
    val columnSpacing: Int = 1,
) : StatefulWidget<TableState> {

    override fun render(area: Rect, buf: Buffer, state: TableState) {
        buf.setStyle(area, style)
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty) return

        // Resolve column widths
        val totalSpacing = (columnWidths.size - 1) * columnSpacing
        val availableForCols = inner.width - totalSpacing
        val colWidths = columnWidths.map { it.resolve(availableForCols) }

        var y = inner.y

        // Header
        header?.let { hdr ->
            renderRow(buf, hdr, inner.x, y, colWidths, headerStyle)
            y += 1  // separator
        }

        // Rows
        state.adjustScroll(inner.height - (if (header != null) 1 else 0), rows.size)
        for ((i, row) in rows.drop(state.offset).withIndex()) {
            if (y + i >= inner.bottom) break
            val rowIndex = i + state.offset
            val rowStyle = if (state.selected == rowIndex) highlightStyle else Style.DEFAULT
            renderRow(buf, row, inner.x, y + i, colWidths, rowStyle)
        }
    }

    private fun renderRow(buf: Buffer, row: TableRow, startX: Int, y: Int, colWidths: List<Int>, rowStyle: Style) {
        var x = startX
        for ((colIdx, cell) in row.cells.withIndex()) {
            if (colIdx >= colWidths.size) break
            val w = colWidths[colIdx]
            val text = cell.content.take(w)
            buf.setString(x, y, text, cell.style.patch(rowStyle))
            x += w + columnSpacing
        }
    }
}

data class TableRow(val cells: List<TableCell>) {
    constructor(vararg cells: String) : this(cells.map { TableCell(it) })
}

data class TableCell(val content: String, val style: Style = Style.DEFAULT)

class TableState(var selected: Int = 0, var offset: Int = 0) {
    fun selectNext(rowCount: Int) { selected = (selected + 1).coerceAtMost(rowCount - 1) }
    fun selectPrevious() { selected = (selected - 1).coerceAtLeast(0) }

    internal fun adjustScroll(visibleHeight: Int, rowCount: Int) {
        if (selected < offset) offset = selected
        if (selected >= offset + visibleHeight) offset = selected - visibleHeight + 1
        offset = offset.coerceIn(0, (rowCount - visibleHeight).coerceAtLeast(0))
    }
}
```

### Gauge

```kotlin
class Gauge(
    val ratio: Double,  // 0.0-1.0
    val label: Line? = null,
    val block: BlockWidget? = null,
    val filledStyle: Style = Style.DEFAULT.fg(Color.Green),
    val unfilledStyle: Style = Style.DEFAULT.fg(Color.DarkGray),
    val filledSymbol: String = "█",
    val unfilledSymbol: String = "░",
) : Widget {
    constructor(percent: Int, label: String? = null, block: BlockWidget? = null)
        : this(percent / 100.0, label?.let { Line(it) }, block)

    override fun render(area: Rect, buf: Buffer) {
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty) return

        val filled = (inner.width * ratio.coerceIn(0.0, 1.0)).toInt()
        for (x in inner.left until inner.left + filled) {
            buf[x, inner.top].apply { symbol = filledSymbol; style = filledStyle }
        }
        for (x in inner.left + filled until inner.right) {
            buf[x, inner.top].apply { symbol = unfilledSymbol; style = unfilledStyle }
        }
        label?.let { l ->
            val labelX = inner.x + (inner.width - l.width) / 2
            l.render(buf, labelX, inner.top, inner.width)
        }
    }
}
```

### Tabs

```kotlin
class Tabs(
    val titles: List<Line>,
    val selected: Int = 0,
    val block: BlockWidget? = null,
    val style: Style = Style.DEFAULT,
    val highlightStyle: Style = Style.DEFAULT.bold().fg(Color.Yellow),
    val divider: String = " │ ",
) : Widget {
    override fun render(area: Rect, buf: Buffer) {
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty) return

        var x = inner.x
        for ((i, title) in titles.withIndex()) {
            if (x >= inner.right) break
            if (i > 0) {
                buf.setString(x, inner.y, divider, style)
                x += divider.length
            }
            val titleStyle = if (i == selected) highlightStyle else style
            for (span in title.spans) {
                buf.setString(x, inner.y, span.content, span.style.patch(titleStyle))
                x += span.width
            }
        }
    }
}
```

## Coroutine-Based Event Loop

### Event Flow

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Turns pollEvent into a non-blocking Flow. */
fun TerminalBackend.eventFlow(pollIntervalMillis: Long = 10): Flow<TerminalEvent> = flow {
    while (currentCoroutineContext().isActive) {
        val event = pollEvent(pollIntervalMillis)
        if (event != null) emit(event)
        else yield()  // cooperative cancellation point
    }
}.flowOn(Dispatchers.Default)

/** Ticks at a fixed frame rate (for animations). */
fun tickFlow(intervalMillis: Long): Flow<Unit> = flow {
    while (currentCoroutineContext().isActive) {
        emit(Unit)
        delay(intervalMillis)
    }
}
```

### App Trait

```kotlin
/** Base class for terminal applications with structured lifecycle. */
abstract class TuiApp {
    protected abstract fun draw(frame: Frame)
    protected abstract suspend fun handleEvent(event: TerminalEvent): Boolean  // true = continue

    /** Optional: called every tick for animations. Override to update state. */
    protected open fun tick() {}

    fun run(backend: TerminalBackend, frameRateMillis: Long = 16L) = runBlocking {
        val terminal = Terminal(backend)
        terminal.init()
        try {
            val events = backend.eventFlow()
            val ticks = tickFlow(frameRateMillis)

            coroutineScope {
                // Render loop
                val renderJob = launch {
                    ticks.collect {
                        tick()
                        terminal.draw { frame -> draw(frame) }
                    }
                }
                // Event loop
                val eventJob = launch {
                    events.collect { event ->
                        if (!handleEvent(event)) {
                            renderJob.cancel()
                            this@coroutineScope.cancel()
                        }
                    }
                }
            }
        } finally {
            terminal.restore()
        }
    }
}
```

### Concrete Example: File Browser

```kotlin
class FileBrowser(private val items: List<String>) : TuiApp() {
    private val listState = ListState()
    private var searchQuery = ""
    private var mode: Mode = Mode.Normal

    enum class Mode { Normal, Search }

    override fun draw(frame: Frame) {
        val (header, body, footer) = Layout.vertical(length(3), fill(), length(1)).split(frame.area)

        // Header
        frame.renderWidget(
            Paragraph("File Browser", Style.DEFAULT.bold(), BlockWidget.bordered("Navigation")),
            header
        )

        // File list
        val filteredItems = items.filter { searchQuery.isEmpty() || it.contains(searchQuery, ignoreCase = true) }
        val listItems = filteredItems.map { ListItem(it) }
        val list = ListWidget(
            items = listItems,
            block = BlockWidget.bordered("Files (${filteredItems.size})"),
            highlightStyle = Style.DEFAULT.fg(Color.Cyan).reversed(),
        )
        frame.renderStatefulWidget(list, body, listState)

        // Footer
        val footerText = when (mode) {
            Mode.Normal -> line("q".bold(), " quit  ".styled(Style.DEFAULT), "/".bold(), " search".styled(Style.DEFAULT))
            Mode.Search -> line("Search: ".styled(Style.DEFAULT), searchQuery.yellow(), "█".dim())
        }
        frame.renderWidget(object : Widget {
            override fun render(area: Rect, buf: Buffer) { footerText.render(buf, area.x, area.y, area.width) }
        }, footer)
    }

    override suspend fun handleEvent(event: TerminalEvent): Boolean {
        if (event !is TerminalEvent.Key) return true
        val key = event.code
        val mods = event.modifiers

        when (mode) {
            Mode.Normal -> when {
                key == KeyCode.Char('q') -> return false
                key == KeyCode.Char('/') -> mode = Mode.Search
                key == KeyCode.Up || key == KeyCode.Char('k') -> listState.selectPrevious()
                key == KeyCode.Down || key == KeyCode.Char('j') -> listState.selectNext(items.size)
                key == KeyCode.Home || key == KeyCode.Char('g') -> listState.selectFirst()
                key == KeyCode.End || key == KeyCode.Char('G') -> listState.selectLast(items.size)
                KeyModifiers.CONTROL in mods && key == KeyCode.Char('c') -> return false
            }
            Mode.Search -> when {
                key == KeyCode.Escape -> { mode = Mode.Normal; searchQuery = "" }
                key == KeyCode.Enter -> mode = Mode.Normal
                key == KeyCode.Backspace -> searchQuery = searchQuery.dropLast(1)
                key is KeyCode.Char -> searchQuery += key.c
            }
        }
        return true
    }
}

// Entry point:
fun main() {
    val files = listOf("README.md", "build.gradle.kts", "src/main.kt", "src/app.kt", "settings.gradle.kts")
    FileBrowser(files).run(JvmTerminalBackend())  // or PosixTerminalBackend() on native
}
```

## Testing Widgets

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class ListWidgetTest {
    @Test
    fun `renders items with selection highlight`() {
        val backend = TestBackend(40, 10)
        val terminal = Terminal(backend)
        val state = ListState(selected = 1)

        terminal.draw { frame ->
            val list = ListWidget(
                items = listOf(ListItem("Apple"), ListItem("Banana"), ListItem("Cherry")),
                highlightSymbol = "> ",
            )
            frame.renderStatefulWidget(list, frame.area, state)
        }

        // Assert selection marker on row 1
        backend.assertRow(1, "> Banana")
    }

    @Test
    fun `selectNext wraps at bounds`() {
        val state = ListState(selected = 2)
        state.selectNext(3)
        assertEquals(2, state.selected)  // stays at last
    }
}

class ParagraphTest {
    @Test
    fun `renders wrapped text within area`() {
        val backend = TestBackend(20, 5)
        val terminal = Terminal(backend)

        terminal.draw { frame ->
            val p = Paragraph("Hello World from a long paragraph", wrap = true)
            frame.renderWidget(p, frame.area)
        }

        backend.assertRow(0, "Hello World from a")
        backend.assertRow(1, "long paragraph")
    }
}
```

## Advanced Patterns

### Popup / Modal

```kotlin
fun Frame.renderPopup(title: String, message: String, widthPct: Int = 60, heightPct: Int = 30) {
    val popupArea = area.centered(percentage(widthPct), percentage(heightPct))
    // Clear background
    renderWidget(Widget { area, buf -> buf.fill(area, " ", Style.DEFAULT) }, popupArea)
    // Render popup content
    renderWidget(
        Paragraph(message, block = BlockWidget.bordered(title), alignment = Alignment.CENTER, wrap = true),
        popupArea
    )
}
```

### Scrollable Viewport

```kotlin
class ScrollableViewport(
    val content: Widget,
    val contentHeight: Int,  // total logical height
) : StatefulWidget<ScrollState> {
    override fun render(area: Rect, buf: Buffer, state: ScrollState) {
        state.visibleHeight = area.height
        state.contentHeight = contentHeight
        // Render content with vertical offset via internal buffer + copy
        val contentBuf = Buffer(area.width, contentHeight)
        content.render(Rect(0, 0, area.width, contentHeight), contentBuf)
        // Copy visible portion
        for (y in 0 until area.height) {
            val srcY = y + state.offset
            if (srcY >= contentHeight) break
            for (x in 0 until area.width) {
                buf[area.x + x, area.y + y].apply {
                    symbol = contentBuf[x, srcY].symbol
                    style = contentBuf[x, srcY].style
                }
            }
        }
    }
}

class ScrollState(var offset: Int = 0, var visibleHeight: Int = 0, var contentHeight: Int = 0) {
    fun scrollDown(lines: Int = 1) { offset = (offset + lines).coerceAtMost((contentHeight - visibleHeight).coerceAtLeast(0)) }
    fun scrollUp(lines: Int = 1) { offset = (offset - lines).coerceAtLeast(0) }
    fun scrollToTop() { offset = 0 }
    fun scrollToBottom() { offset = (contentHeight - visibleHeight).coerceAtLeast(0) }
    val scrollPercentage: Float get() = if (contentHeight <= visibleHeight) 1f else offset.toFloat() / (contentHeight - visibleHeight)
}
```

### Input Field

```kotlin
class InputField(
    val label: String = "",
    val block: BlockWidget? = null,
    val style: Style = Style.DEFAULT,
    val cursorStyle: Style = Style.DEFAULT.reversed(),
) : StatefulWidget<InputState> {
    override fun render(area: Rect, buf: Buffer, state: InputState) {
        block?.render(area, buf)
        val inner = block?.inner(area) ?: area
        if (inner.isEmpty) return

        // Render label + text
        val labelWidth = if (label.isNotEmpty()) label.length + 1 else 0
        if (label.isNotEmpty()) buf.setString(inner.x, inner.y, "$label ", style.dim())

        val textX = inner.x + labelWidth
        val visibleWidth = inner.width - labelWidth
        val displayText = state.value.drop(state.scrollOffset).take(visibleWidth)
        buf.setString(textX, inner.y, displayText, style)

        // Cursor
        val cursorX = textX + state.cursor - state.scrollOffset
        if (cursorX in inner.left until inner.right) {
            val cursorChar = state.value.getOrNull(state.cursor)?.toString() ?: " "
            buf[cursorX, inner.y].apply { symbol = cursorChar; this.style = cursorStyle }
        }
    }
}

class InputState(initial: String = "") {
    var value: String = initial; private set
    var cursor: Int = initial.length; private set
    var scrollOffset: Int = 0; private set

    fun insert(c: Char) { value = value.substring(0, cursor) + c + value.substring(cursor); cursor++ }
    fun backspace() { if (cursor > 0) { value = value.removeRange(cursor - 1, cursor); cursor-- } }
    fun delete() { if (cursor < value.length) { value = value.removeRange(cursor, cursor + 1) } }
    fun moveLeft() { cursor = (cursor - 1).coerceAtLeast(0) }
    fun moveRight() { cursor = (cursor + 1).coerceAtMost(value.length) }
    fun moveHome() { cursor = 0 }
    fun moveEnd() { cursor = value.length }
    fun clear() { value = ""; cursor = 0 }

    /** Call after resize or cursor movement to keep cursor visible. */
    fun adjustScroll(visibleWidth: Int) {
        if (cursor < scrollOffset) scrollOffset = cursor
        if (cursor >= scrollOffset + visibleWidth) scrollOffset = cursor - visibleWidth + 1
    }
}
```

### Multiple Screens / Router

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

class Router {
    private val stack = mutableListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) { stack.add(screen) }
    fun pop(): Boolean { if (stack.size > 1) { stack.removeLast(); return true }; return false }
    fun replace(screen: Screen) { stack[stack.lastIndex] = screen }
}

// In your app:
class MyApp : TuiApp() {
    private val router = Router()

    override fun draw(frame: Frame) {
        when (val screen = router.current) {
            Screen.Home -> drawHome(frame)
            Screen.Settings -> drawSettings(frame)
            is Screen.Detail -> drawDetail(frame, screen.id)
        }
    }

    override suspend fun handleEvent(event: TerminalEvent): Boolean {
        if (event is TerminalEvent.Key && event.code == KeyCode.Escape) {
            if (!router.pop()) return false  // exit if can't go back
            return true
        }
        // dispatch to current screen handler...
        return true
    }
}
```

## Performance Tips

- **Bitmask modifiers** — use `Int` bitfield instead of `Set<Modifier>` (zero alloc per style).
- **Buffer reuse** — consider pre-allocating a max-size buffer and clearing instead of creating new each frame.
- **Minimize String alloc** — use `" "` constant for blank cells, not `Char.toString()` every frame.
- **Diff rendering** — the Terminal class only flushes changed cells; keep state stable between frames.
- **Avoid Flow allocations in tight loops** — the event flow approach is fine for 60fps but if you need higher throughput, use a Channel directly.
- **Inline widget lambdas** — mark short widget factories `inline` to avoid lambda object allocation.
- **String.length vs display width** — for CJK/emoji support, implement proper `wcwidth()` via a lookup table. The placeholder `unicodeWidth()` in kmp-core.md is a starting point.
