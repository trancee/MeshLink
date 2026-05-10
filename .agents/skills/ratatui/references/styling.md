# Styling and Text Reference

## Style

```rust
use ratatui::style::{Style, Color, Modifier};

let style = Style::default()
    .fg(Color::Red)
    .bg(Color::Black)
    .add_modifier(Modifier::BOLD | Modifier::ITALIC);

// Reset specific attributes
let style = Style::reset(); // clear all styling

// Const-compatible (0.30.0+) — Stylize methods work in const context:
const MY_STYLE: Style = Style::new().blue().on_black().bold();
```

## Colors

```rust
// Named colors (ANSI 16):
Color::Reset              // terminal default
Color::Black, Color::Red, Color::Green, Color::Yellow,
Color::Blue, Color::Magenta, Color::Cyan, Color::White,
Color::Gray, Color::DarkGray,
Color::LightRed, Color::LightGreen, Color::LightYellow,
Color::LightBlue, Color::LightMagenta, Color::LightCyan,

// True color (24-bit):
Color::Rgb(r, g, b)

// 256-color palette:
Color::Indexed(n)         // 0-255

// From tuples/arrays (0.30.0+):
Color::from([255, 0, 0])        // RGB array
Color::from((255, 0, 0))        // RGB tuple
Color::from([255, 0, 0, 255])   // RGBA (alpha ignored for terminal)
```

## Modifiers

```rust
Modifier::BOLD
Modifier::DIM
Modifier::ITALIC
Modifier::UNDERLINED
Modifier::SLOW_BLINK
Modifier::RAPID_BLINK
Modifier::REVERSED
Modifier::HIDDEN
Modifier::CROSSED_OUT
```

Combine with bitwise OR: `Modifier::BOLD | Modifier::ITALIC`

## Stylize Trait (Fluent API)

```rust
use ratatui::style::Stylize;

// Apply to any widget or text element:
"hello".bold().red().on_blue()

// Chain styles on widgets:
Paragraph::new("text").bold().italic().fg(Color::Green)

// Works on primitives (0.30.0+):
let s = Cow::Borrowed("text");
s.red()  // works on Cow<str>, u8, i32, f64, etc.
```

The `Stylize` trait provides methods: `.bold()`, `.dim()`, `.italic()`, `.underlined()`,
`.reversed()`, `.crossed_out()`, `.red()`, `.green()`, `.blue()`, `.yellow()`, `.cyan()`,
`.magenta()`, `.white()`, `.gray()`, `.dark_gray()`, `.light_red()`, etc.,
`.on_red()`, `.on_blue()`, etc. (background), `.fg(Color)`, `.bg(Color)`.

## Text Rendering

### Hierarchy: Text > Line > Span

```rust
use ratatui::text::{Text, Line, Span};

// Span — styled fragment (inline)
let span = Span::styled("hello", Style::default().fg(Color::Red));
let span = Span::raw("unstyled");
let span = "hello".red().bold(); // via Stylize

// Line — horizontal sequence of Spans (one row)
let line = Line::from(vec![
    "Hello ".into(),
    "World".red().bold(),
]);
let line = Line::from("simple text");

// Text — vertical sequence of Lines (multi-line)
let text = Text::from("multi\nline\ntext");
let text = Text::from(vec![
    Line::from("first line"),
    Line::from(vec!["styled ".into(), "second".yellow()]),
]);

// Text supports += (0.30.0+):
let mut text = Text::from("line 1");
text += Text::from("line 2");
```

### Alignment

```rust
use ratatui::layout::Alignment;

// On a Line:
Line::from("centered").alignment(Alignment::Center);
// Shorthand:
Line::from("centered").centered();
Line::from("right").right_aligned();

// On a Paragraph:
Paragraph::new(text).alignment(Alignment::Center);
```

Note: `Alignment` has been renamed to `HorizontalAlignment` in 0.30.0 (old name still works as alias).

### Width

```rust
// Get the display width of text elements:
let width = span.width();    // single span width
let width = line.width();    // total line width
let width = text.width();    // widest line width
```

## Symbols and Line Sets

```rust
use ratatui::symbols;

// Border characters
symbols::border::PLAIN      // ─│┌┐└┘
symbols::border::ROUNDED    // ─│╭╮╰╯
symbols::border::DOUBLE     // ═║╔╗╚╝
symbols::border::THICK      // ━┃┏┓┗┛

// Line characters (for LineGauge)
symbols::line::NORMAL       // ─
symbols::line::THICK        // ━
symbols::line::DOUBLE       // ═

// Bar characters (for Sparkline)
symbols::bar::NINE_LEVELS   // ▁▂▃▄▅▆▇█ (most resolution)
symbols::bar::THREE_LEVELS  // ▄█ (basic)
symbols::bar::HALF          // ▄
symbols::bar::FULL          // █

// Block characters
symbols::block::FULL        // █
symbols::block::SEVEN_EIGHTHS // ▉
// ... down to ONE_EIGHTH

// Markers (for Chart/Canvas)
symbols::Marker::Dot        // •
symbols::Marker::Braille    // ⠁⠂... (2x4 per cell, most widely supported)
symbols::Marker::Block      // █
symbols::Marker::Bar        // ▄
symbols::Marker::HalfBlock  // ▀▄ (2 vertical pixels per cell)
symbols::Marker::Quadrant   // ▌▞▛ (2x2 per cell, dense, no bands) — 0.30.0+
symbols::Marker::Sextant    // 🬪🬫🬬 (2x3 per cell) — 0.30.0+
symbols::Marker::Octant     // 𜶟𜶠𜶡 (2x4 per cell, like Braille but dense) — 0.30.0+
```

Note: `Marker` enum is `#[non_exhaustive]` — always include a wildcard arm when matching.

## Color Theming Pattern

```rust
struct Theme {
    bg: Color,
    fg: Color,
    highlight: Color,
    border: Color,
    title: Style,
}

impl Theme {
    fn default_dark() -> Self {
        Self {
            bg: Color::Rgb(30, 30, 46),
            fg: Color::Rgb(205, 214, 244),
            highlight: Color::Rgb(137, 180, 250),
            border: Color::Rgb(88, 91, 112),
            title: Style::new().fg(Color::Rgb(137, 180, 250)).bold(),
        }
    }
}
```
