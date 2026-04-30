# Built-in Widgets Reference

All widgets implement the `Widget` trait and are rendered with `frame.render_widget(widget, area)`.
Stateful widgets use `frame.render_stateful_widget(widget, area, &mut state)`.

## Block (Container/Border)

```rust
use ratatui::widgets::{Block, Borders, BorderType, Padding};

let block = Block::default()
    .title("Title")                     // accepts Into<Line> (0.30.0+; Title type removed)
    .borders(Borders::ALL)              // ALL, NONE, TOP, BOTTOM, LEFT, RIGHT
    .border_type(BorderType::Rounded)   // Plain, Rounded, Double, Thick, QuadrantInside, QuadrantOutside
    .border_style(Style::default().fg(Color::White))
    .style(Style::default().bg(Color::Black))
    .padding(Padding::new(1, 1, 0, 0)); // left, right, top, bottom

// Shorthand for bordered block:
let block = Block::bordered().title("Title");

// Title alignment via Line (0.30.0+):
let block = Block::bordered().title(Line::from("Centered Title").centered());

// Additional border types (0.30.0+):
// BorderType::LightDoubleDashed, HeavyDoubleDashed, LightTripleDashed,
// HeavyTripleDashed, LightQuadrupleDashed, HeavyQuadrupleDashed

// Border merging (0.30.0+): overlapping borders automatically merge into clean borders

// Use Block with inner widgets:
let inner_area = block.inner(area); // get the area inside borders
frame.render_widget(block, area);
frame.render_widget(paragraph, inner_area);

// Or use block as part of another widget:
Paragraph::new("text").block(Block::bordered().title("Content"));
```

## Paragraph

```rust
use ratatui::widgets::{Paragraph, Wrap};

let paragraph = Paragraph::new(text)
    .block(Block::default().title("Content").borders(Borders::ALL))
    .style(Style::default())
    .alignment(Alignment::Left)
    .wrap(Wrap { trim: true })  // enable word wrapping; trim removes leading whitespace
    .scroll((vertical_offset, 0)); // (y, x) scroll offset
```

## List

```rust
use ratatui::widgets::{List, ListItem, ListState, ListDirection};

let items = vec![
    ListItem::new("Item 1"),
    ListItem::new(Line::from(vec!["colored ".into(), "item".red()])),
    ListItem::new("Item 3").style(Style::default().fg(Color::Yellow)),
];

let list = List::new(items)
    .block(Block::default().title("List").borders(Borders::ALL))
    .style(Style::default())
    .highlight_style(Style::default().add_modifier(Modifier::BOLD).bg(Color::DarkGray))
    .highlight_symbol(">> ")
    .highlight_spacing(HighlightSpacing::Always) // Always, WhenSelected, Never
    .direction(ListDirection::TopToBottom); // or BottomToTop

// Stateful (for selection):
let mut state = ListState::default();
state.select(Some(0)); // select first item
frame.render_stateful_widget(list, area, &mut state);

// Navigation:
state.select_next();     // move selection down
state.select_previous(); // move selection up
state.select_first();
state.select_last();
```

## Table

```rust
use ratatui::widgets::{Table, Row, Cell, TableState};

let header = Row::new(vec![
    Cell::from("Col 1").style(Style::default().bold()),
    Cell::from("Col 2"),
    Cell::from("Col 3"),
]).height(1).bottom_margin(1);

let rows = vec![
    Row::new(vec!["r1c1", "r1c2", "r1c3"]),
    Row::new(vec!["r2c1", "r2c2", "r2c3"]).height(2),
];

let table = Table::new(rows, [
    Constraint::Length(10),      // column widths
    Constraint::Min(5),
    Constraint::Percentage(30),
])
    .header(header)
    .block(Block::default().title("Table").borders(Borders::ALL))
    .highlight_style(Style::default().bg(Color::DarkGray))
    .highlight_symbol(">> ")
    .row_highlight_style(Style::default())  // style for selected row
    .column_spacing(1);

// Stateful (for row selection):
let mut state = TableState::default();
state.select(Some(0));
frame.render_stateful_widget(table, area, &mut state);
```

## Tabs

```rust
use ratatui::widgets::Tabs;

let titles = vec!["Tab1", "Tab2", "Tab3"];
let tabs = Tabs::new(titles)
    .block(Block::default().title("Tabs").borders(Borders::ALL))
    .select(selected_index)
    .style(Style::default())
    .highlight_style(Style::default().bold().fg(Color::Yellow))
    .divider("|");
```

## Gauge / LineGauge

```rust
use ratatui::widgets::{Gauge, LineGauge};

// Block gauge (fills area)
let gauge = Gauge::default()
    .block(Block::default().title("Progress").borders(Borders::ALL))
    .gauge_style(Style::default().fg(Color::Green).bg(Color::Black))
    .percent(42)            // 0-100
    // .ratio(0.42)         // alternative: 0.0-1.0
    .label("42%");          // custom label (centered)

// Line gauge (single line)
let line_gauge = LineGauge::default()
    .block(Block::default().title("Download").borders(Borders::ALL))
    .filled_style(Style::default().fg(Color::Green))
    .unfilled_style(Style::default().fg(Color::DarkGray))
    .filled_symbol("█")      // customizable symbols (0.30.0+)
    .unfilled_symbol("░")
    .ratio(0.42);
```

## BarChart

```rust
use ratatui::widgets::{BarChart, Bar, BarGroup};

// Simple:
let barchart = BarChart::default()
    .block(Block::default().title("Stats").borders(Borders::ALL))
    .data(&[("Mon", 10), ("Tue", 20), ("Wed", 15)])
    .bar_width(5)
    .bar_gap(1)
    .bar_style(Style::default().fg(Color::Green))
    .value_style(Style::default().bold());

// New constructors (0.30.0+):
let barchart = BarChart::grouped(vec![
    BarGroup::with_label("Group 1", vec![
        Bar::with_label("A", 10),
        Bar::with_label("B", 20),
    ]),
    BarGroup::with_label("Group 2", vec![
        Bar::with_label("C", 30),
        Bar::with_label("D", 40),
    ]),
]);

// Also: BarChart::new, BarChart::vertical, BarChart::horizontal
```

## Chart (Line/Scatter)

```rust
use ratatui::widgets::{Chart, Dataset, Axis, GraphType};
use ratatui::symbols;

let datasets = vec![
    Dataset::default()
        .name("Series 1")
        .marker(symbols::Marker::Braille) // Braille, Dot, Bar, Block, HalfBlock
        .graph_type(GraphType::Line)      // Line or Scatter
        .style(Style::default().fg(Color::Cyan))
        .data(&[(0.0, 1.0), (1.0, 3.0), (2.0, 2.0), (3.0, 4.0)]),
    Dataset::default()
        .name("Series 2")
        .marker(symbols::Marker::Dot)
        .graph_type(GraphType::Scatter)
        .style(Style::default().fg(Color::Red))
        .data(&[(0.0, 2.0), (1.0, 1.0), (2.0, 4.0)]),
];

let chart = Chart::new(datasets)
    .block(Block::default().title("Chart").borders(Borders::ALL))
    .x_axis(
        Axis::default()
            .title("X")
            .style(Style::default().fg(Color::Gray))
            .bounds([0.0, 4.0])          // visible range
            .labels(vec!["0".into(), "2".into(), "4".into()])
    )
    .y_axis(
        Axis::default()
            .title("Y")
            .style(Style::default().fg(Color::Gray))
            .bounds([0.0, 5.0])
            .labels(vec!["0".into(), "2.5".into(), "5".into()])
    );
```

## Sparkline

```rust
use ratatui::widgets::Sparkline;

let sparkline = Sparkline::default()
    .block(Block::default().title("Spark").borders(Borders::ALL))
    .data(&[1, 4, 2, 8, 5, 3, 7, 2, 6])
    .max(10)                     // optional ceiling
    .style(Style::default().fg(Color::Green))
    .bar_set(symbols::bar::NINE_LEVELS); // character set for bars
```

## Canvas

```rust
use ratatui::widgets::canvas::{Canvas, Line as CanvasLine, Rectangle, Circle, Map, MapResolution, Points};

let canvas = Canvas::default()
    .block(Block::default().title("Canvas").borders(Borders::ALL))
    .x_bounds([-180.0, 180.0])
    .y_bounds([-90.0, 90.0])
    .marker(symbols::Marker::Braille)
    .paint(|ctx| {
        ctx.draw(&Map {
            color: Color::White,
            resolution: MapResolution::High,
        });
        ctx.draw(&CanvasLine {
            x1: 0.0, y1: 0.0,
            x2: 10.0, y2: 10.0,
            color: Color::Red,
        });
        ctx.draw(&Rectangle {
            x: -5.0, y: -5.0,
            width: 10.0, height: 10.0,
            color: Color::Green,
        });
        ctx.draw(&Circle {
            x: 0.0, y: 0.0,
            radius: 5.0,
            color: Color::Yellow,
        });
        ctx.layer(); // start new layer (avoids overdraw)
        ctx.print(0.0, 0.0, "label".yellow());
    });
```

## Scrollbar

```rust
use ratatui::widgets::{Scrollbar, ScrollbarOrientation, ScrollbarState};

let scrollbar = Scrollbar::new(ScrollbarOrientation::VerticalRight)
    .begin_symbol(Some("↑"))
    .end_symbol(Some("↓"))
    .track_symbol(Some("│"))
    .thumb_symbol("█");

let mut scrollbar_state = ScrollbarState::new(content_length)
    .position(current_position);

frame.render_stateful_widget(scrollbar, area, &mut scrollbar_state);
```

## Clear

```rust
use ratatui::widgets::Clear;

// Clears an area (useful before drawing popups over existing content)
frame.render_widget(Clear, popup_area);
frame.render_widget(popup_widget, popup_area);
```
