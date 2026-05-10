# Layout System Reference

## Rect

```rust
pub struct Rect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}
```

All positioning uses `Rect`. Widgets never know their absolute position — they render into whatever `Rect` they receive.

**Useful methods:**
- `rect.left()`, `rect.right()`, `rect.top()`, `rect.bottom()` — edges
- `rect.area()` — total cells
- `rect.inner(margin)` — shrink by margin
- `rect.outer(margin)` — grow by margin (0.30.0+)
- `rect.centered(h_constraint, v_constraint)` — center a sub-rect (0.30.0+)
- `rect.centered_vertically(constraint)` — vertical centering only (0.30.0+)
- `rect.centered_horizontally(constraint)` — horizontal centering only (0.30.0+)
- `rect.layout(&layout)` — split using a layout, returns fixed-size array (0.30.0+)
- `rect.try_layout(&layout)` — fallible version returning Result (0.30.0+)
- `rect.layout_vec(&layout)` — returns Vec (0.30.0+)

## Layout

```rust
use ratatui::layout::{Layout, Constraint, Direction, Flex};

// Preferred: use Layout::vertical / Layout::horizontal + .areas()
let vertical = Layout::vertical([Constraint::Length(3), Constraint::Min(0), Constraint::Length(3)]);
let [header, main, footer] = vertical.areas(frame.area());

let horizontal = Layout::horizontal([Constraint::Fill(1); 2]);
let [left, right] = horizontal.areas(main);
```

The `.areas()` method returns a fixed-size array (inferred at compile time from constraint count).

### Alternative Methods

```rust
// .split() returns Rc<[Rect]> (legacy, still works):
let chunks = Layout::default()
    .direction(Direction::Vertical)
    .margin(1)                          // uniform margin
    // .horizontal_margin(2)            // or specific
    // .vertical_margin(1)
    .constraints([Constraint::Length(3), Constraint::Min(0)])
    .split(frame.area());

// Rect::layout() — call layout from the Rect (0.30.0+):
let [top, main] = area.layout(&Layout::vertical([Constraint::Fill(1); 2]));

// Layout::try_areas() — returns Result (0.30.0+):
let [top, main] = layout.try_areas(area)?;
```

## Direction

- `Direction::Vertical` — stack top-to-bottom (constraints control height)
- `Direction::Horizontal` — stack left-to-right (constraints control width)

## Constraints

In priority order for the solver:
- `Constraint::Length(n)` — exactly n cells
- `Constraint::Min(n)` — at least n cells
- `Constraint::Max(n)` — at most n cells
- `Constraint::Percentage(n)` — n% of available space
- `Constraint::Ratio(num, den)` — fraction of available space
- `Constraint::Fill(weight)` — fill proportionally by weight (like CSS flex-grow)

## Flex

Controls how leftover space is distributed:
- `Flex::Start` — pack toward start (default)
- `Flex::End` — pack toward end
- `Flex::Center` — center all chunks
- `Flex::SpaceAround` — space between items is twice edge spacing (like CSS flexbox)
- `Flex::SpaceBetween` — equal gaps between chunks, no space at edges
- `Flex::SpaceEvenly` — equal gaps between items and edges (was SpaceAround pre-0.30)
- `Flex::Legacy` — pre-0.26 behavior

## Nested Layouts

```rust
let outer = Layout::vertical([Constraint::Length(3), Constraint::Min(0)]).split(area);
let inner = Layout::horizontal([Constraint::Percentage(50), Constraint::Percentage(50)])
    .split(outer[1]);
// inner[0] = left half of bottom section
// inner[1] = right half of bottom section
```

Or with the array-returning API:

```rust
let [header, body] = Layout::vertical([Constraint::Length(3), Constraint::Min(0)]).areas(area);
let [left, right] = Layout::horizontal([Constraint::Percentage(50), Constraint::Percentage(50)]).areas(body);
```

## Centering

```rust
// Preferred (0.30.0+):
let popup_area = frame.area().centered(Constraint::Percentage(60), Constraint::Percentage(20));

// Vertical only:
let area = frame.area().centered_vertically(Constraint::Ratio(1, 2));

// Horizontal only:
let area = frame.area().centered_horizontally(Constraint::Length(40));

// Manual approach (still works, more control):
fn centered_rect(percent_x: u16, percent_y: u16, area: Rect) -> Rect {
    let vertical = Layout::vertical([
        Constraint::Percentage((100 - percent_y) / 2),
        Constraint::Percentage(percent_y),
        Constraint::Percentage((100 - percent_y) / 2),
    ]).split(area);

    Layout::horizontal([
        Constraint::Percentage((100 - percent_x) / 2),
        Constraint::Percentage(percent_x),
        Constraint::Percentage((100 - percent_x) / 2),
    ]).split(vertical[1])[1]
}
```

## Common Layout Patterns

### Header + Content + Footer

```rust
use Constraint::{Length, Min};
let [header, content, footer] = Layout::vertical([Length(1), Min(0), Length(1)]).areas(frame.area());
```

### Sidebar + Main

```rust
let [sidebar, main] = Layout::horizontal([Constraint::Length(20), Constraint::Min(0)]).areas(frame.area());
```

### Three-Column

```rust
let [left, center, right] = Layout::horizontal([Constraint::Fill(1); 3]).areas(frame.area());
```

### Full App Layout

```rust
use Constraint::{Fill, Length, Min};

let [title_area, main_area, status_area] = Layout::vertical([Length(1), Min(0), Length(1)]).areas(frame.area());
let [left_area, right_area] = Layout::horizontal([Fill(1); 2]).areas(main_area);

frame.render_widget(Block::bordered().title("Title Bar"), title_area);
frame.render_widget(Block::bordered().title("Status Bar"), status_area);
frame.render_widget(Block::bordered().title("Left"), left_area);
frame.render_widget(Block::bordered().title("Right"), right_area);
```
