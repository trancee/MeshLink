---
name: ratatui
description: Ratatui reference for building rich terminal UIs, plus Kotlin Multiplatform TUI patterns. Rust coverage includes Terminal/Frame/Backend/Buffer architecture, immediate-mode rendering, Layout system (Constraint, Direction, Flex), all built-in widgets (Block, Paragraph, List, Table, Tabs, Chart, BarChart, Gauge, Sparkline, Canvas, Scrollbar), text rendering (Text/Line/Span), styling (Style, Color, Modifier), crossterm event handling, and app architecture (main loop, setup/restore, alternate screen, raw mode). KMP coverage maps the architecture to Kotlin (expect/actual backends, Buffer diff-rendering, Widget/StatefulWidget, Layout solver, styled text, ANSI rendering, coroutine event loop, DSL builders, practical widgets, TestBackend). Use when building a TUI in Rust or KMP, writing widgets, designing layouts, or any ratatui question.
---

<essential_principles>

## Ratatui 0.30.0

Ratatui is a Rust library for building terminal user interfaces. Forked from tui-rs in 2023.

### Core Concept: Immediate-Mode Rendering

Every frame, you describe the entire UI from scratch. No retained widget tree. The library diffs buffers internally for efficient terminal I/O.

```rust
terminal.draw(|frame| {
    frame.render_widget(my_widget, frame.area());
})?;
```

### Installation

```toml
[dependencies]
ratatui = "0.30"       # crossterm backend is default; no separate crossterm dep needed
```

Since 0.27.0, ratatui re-exports the backend crate — access via `ratatui::crossterm`.

Alternative backends:
```toml
ratatui = { version = "0.30", default-features = false, features = ["termion"] }
ratatui = { version = "0.30", default-features = false, features = ["termwiz"] }
```

**Default features:** `crossterm`, `all-widgets`, `macros`, `layout-cache`, `underline-color`

**Optional features:** `serde`, `palette`, `scrolling-regions`, `portable-atomic`, `unstable-widget-ref`

**Crate organization (0.30.0):**
- `ratatui` — main crate for applications (re-exports everything)
- `ratatui-core` — core traits/types (for widget library authors)
- `ratatui-widgets`, `ratatui-crossterm`, `ratatui-termion`, `ratatui-termwiz`, `ratatui-macros`

### Quick Start

```rust
use ratatui::crossterm::event;

fn main() -> std::io::Result<()> {
    ratatui::run(|terminal| {
        loop {
            terminal.draw(|frame| frame.render_widget("Hello World!", frame.area()))?;
            if event::read()?.is_key_press() {
                break Ok(());
            }
        }
    })
}
```

`ratatui::run()` handles initialization, restoration, and panic hooks automatically. For more control use `ratatui::init()` / `ratatui::restore()`, or construct `Terminal` manually.

### Key Types

- `Terminal<B: Backend>` — owns the backend, manages double-buffering
- `DefaultTerminal` — type alias for crossterm terminal
- `Frame` — provides `render_widget(widget, area)` and `area()`
- `Widget` trait — `fn render(self, area: Rect, buf: &mut Buffer)`
- `StatefulWidget` trait — adds `type State`; rendered with `render_stateful_widget()`
- `Rect` — `{ x, y, width, height }` — all positioning
- `Buffer` — 2D grid of `Cell`s

### Key Tips

- **No separate crossterm dep** — use `ratatui::crossterm::*`
- **`Event::is_key_press()`** — quick event check
- **`Block::bordered()`** — shorthand for `Block::default().borders(Borders::ALL)`
- **`Layout::vertical([...]).areas(rect)`** — returns fixed-size array
- **`rect.centered(h, v)`** — center a sub-rect
- **Const styles:** `const MY_STYLE: Style = Style::new().blue().bold();`
- **Widget library authors:** depend on `ratatui-core` not full `ratatui`
- **`no_std`:** disable default-features for embedded targets

</essential_principles>

<routing>

## Reference Files

Load the relevant reference based on what you need:

| Topic | Reference |
|-------|-----------|
| Widget API — Rust (Block, Paragraph, List, Table, Tabs, Chart, BarChart, Gauge, LineGauge, Sparkline, Canvas, Scrollbar, Clear) | `references/widgets.md` |
| Layout system — Rust (Layout, Constraint, Direction, Flex, Rect, centering, nesting, common patterns) | `references/layout.md` |
| Styling and text — Rust (Style, Color, Modifier, Stylize, Text/Line/Span, symbols, markers, theming) | `references/styling.md` |
| App architecture — Rust (event handling, custom widgets, common recipes — popup, input, routing, testing) | `references/patterns.md` |
| KMP TUI core infrastructure (Rect, Buffer, Style, Color, Layout solver, Terminal backends for JVM/Native, ANSI rendering, escape sequence parsing, TestBackend) | `references/kmp-core.md` |
| KMP TUI widgets and app patterns (styled text model, DSL builders, Block, Paragraph, List, Table, Gauge, Tabs, InputField, Scrollable, coroutine event loop, TuiApp base class, router, testing) | `references/kmp-widgets.md` |

**Loading guidance:**
- For Rust TUI questions → load the relevant Rust reference file.
- For Kotlin/KMP TUI → load `kmp-core.md` for infrastructure, `kmp-widgets.md` for widgets/app patterns, or both.
- For "build a TUI app from scratch in KMP" → load both KMP files.
- For mapping a specific Ratatui concept to Kotlin → load the Rust reference + corresponding KMP file.

</routing>

<reference_index>
All domain knowledge in `references/`:

**Rust — Widgets:** widgets.md — Block, Paragraph, List, Table, Tabs, Chart, BarChart, Gauge, LineGauge, Sparkline, Canvas, Scrollbar, Clear
**Rust — Layout:** layout.md — Layout, Constraint, Direction, Flex, Rect methods, centering, nesting, common patterns
**Rust — Styling:** styling.md — Style, Color, Modifier, Stylize trait, Text/Line/Span, symbols, markers, theming
**Rust — Patterns:** patterns.md — App struct, event handling, async, mouse, panic hooks, custom widgets, recipes, testing
**KMP — Core:** kmp-core.md — Gradle setup, Rect/Margin, Style/Color/Modifier (bitmask), Cell/Buffer (with diff), Layout/Constraint solver, TerminalBackend interface, KeyCode sealed class, JVM backend (JLine 3 + escape seq parser), Native backend (POSIX termios + escape seq parser), Terminal (double-buffered diff rendering), TestBackend, ANSI escape code generation
**KMP — Widgets:** kmp-widgets.md — Styled text (Span/Line/Text + DSL), Widget DSL (FrameScope), BlockWidget, Paragraph, ListWidget+ListState, TableWidget+TableState, Gauge, Tabs, coroutine event loop (eventFlow/tickFlow), TuiApp base class, complete FileBrowser example, testing patterns, popup/modal, ScrollableViewport, InputField+InputState, Router pattern, performance tips
</reference_index>
