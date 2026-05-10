# Application Patterns Reference

## App Architecture

### Recommended Pattern (0.30.0+)

```rust
use std::io;
use ratatui::{DefaultTerminal, Frame};
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind};

struct App {
    running: bool,
    counter: u32,
}

impl App {
    fn new() -> Self {
        Self { running: true, counter: 0 }
    }

    fn run(mut self, terminal: &mut DefaultTerminal) -> io::Result<()> {
        while self.running {
            terminal.draw(|frame| self.draw(frame))?;
            self.handle_events()?;
        }
        Ok(())
    }

    fn draw(&self, frame: &mut Frame) {
        // all rendering logic here
    }

    fn handle_events(&mut self) -> io::Result<()> {
        match event::read()? {
            Event::Key(key) if key.kind == KeyEventKind::Press => {
                self.handle_key(key);
            }
            _ => {}
        }
        Ok(())
    }

    fn handle_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => self.running = false,
            _ => {}
        }
    }
}

fn main() -> io::Result<()> {
    ratatui::run(|terminal| App::new().run(terminal))
}
```

### Minimal Hello World

```rust
use std::io;
use ratatui::{DefaultTerminal, Frame, widgets::{Block, Paragraph}, layout::{Layout, Constraint}};
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEventKind};

fn main() -> io::Result<()> {
    ratatui::run(run_app)
}

fn run_app(terminal: &mut DefaultTerminal) -> io::Result<()> {
    loop {
        terminal.draw(ui)?;
        if let Event::Key(key) = event::read()? {
            if key.kind == KeyEventKind::Press && key.code == KeyCode::Char('q') {
                return Ok(());
            }
        }
    }
}

fn ui(frame: &mut Frame) {
    use Constraint::{Length, Min};
    let [header, content] = Layout::vertical([Length(3), Min(0)]).areas(frame.area());
    frame.render_widget(Paragraph::new("My App (press 'q' to quit)").block(Block::bordered()), header);
    frame.render_widget(Paragraph::new("Hello, ratatui!").block(Block::bordered().title("Content")), content);
}
```

## Event Handling

### Basic (blocking read)

```rust
use ratatui::crossterm::event::{self, Event, KeyCode, KeyEvent, KeyModifiers, KeyEventKind};

// Blocking — waits until an event arrives:
match event::read()? {
    Event::Key(key) if key.kind == KeyEventKind::Press => {
        match key.code {
            KeyCode::Char('q') => return Ok(()),
            KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => return Ok(()),
            KeyCode::Up => { /* handle */ }
            KeyCode::Down => { /* handle */ }
            KeyCode::Enter => { /* handle */ }
            KeyCode::Esc => { /* handle */ }
            KeyCode::Char(c) => { /* typed character */ }
            _ => {}
        }
    }
    Event::Resize(width, height) => { /* terminal resized */ }
    Event::Mouse(mouse) => { /* if mouse capture enabled */ }
    _ => {}
}

// Quick check (0.30.0+):
if event::read()?.is_key_press() { /* any key was pressed */ }
```

### Non-blocking (for animations)

```rust
use std::time::Duration;

// Poll with timeout — returns immediately if no event:
if event::poll(Duration::from_millis(16))? {  // ~60fps
    match event::read()? {
        Event::Key(key) if key.kind == KeyEventKind::Press => { /* handle */ }
        _ => {}
    }
}
// Always redraw (for animations):
terminal.draw(|frame| self.draw(frame))?;
```

### Async Event Stream (with tokio)

```rust
use ratatui::crossterm::event::EventStream;
use futures::StreamExt;

let mut reader = EventStream::new();
loop {
    tokio::select! {
        Some(Ok(event)) = reader.next() => {
            // handle event
        }
        _ = some_async_task => {
            // handle async work
        }
    }
}
```

### Mouse Support

```rust
use ratatui::crossterm::event::{EnableMouseCapture, DisableMouseCapture};
use ratatui::crossterm::execute;

// In setup (manual init only):
execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
// In restore:
execute!(terminal.backend_mut(), LeaveAlternateScreen, DisableMouseCapture)?;
```

## Panic Hook

`ratatui::run()` installs a panic hook automatically. Only needed for manual setup:

```rust
use std::panic;

fn init_panic_hook() {
    let original_hook = panic::take_hook();
    panic::set_hook(Box::new(move |panic_info| {
        ratatui::restore();
        original_hook(panic_info);
    }));
}
```

## Custom Widgets

### Implementing Widget

```rust
use ratatui::{buffer::Buffer, layout::Rect, style::Style, widgets::Widget};

struct MyWidget {
    label: String,
    style: Style,
}

impl Widget for MyWidget {
    fn render(self, area: Rect, buf: &mut Buffer) {
        // Write directly to the buffer
        buf.set_string(area.x, area.y, &self.label, self.style);

        // Or cell-by-cell:
        for x in area.left()..area.right() {
            for y in area.top()..area.bottom() {
                buf.get_mut(x, y)
                    .set_char('█')
                    .set_style(self.style);
            }
        }
    }
}
```

### Implementing StatefulWidget

```rust
struct ScrollableList {
    items: Vec<String>,
}

struct ScrollableListState {
    offset: usize,
    selected: usize,
}

impl StatefulWidget for ScrollableList {
    type State = ScrollableListState;

    fn render(self, area: Rect, buf: &mut Buffer, state: &mut Self::State) {
        let visible_items = area.height as usize;
        if state.selected >= state.offset + visible_items {
            state.offset = state.selected - visible_items + 1;
        }
        if state.selected < state.offset {
            state.offset = state.selected;
        }
        for (i, item) in self.items.iter().skip(state.offset).take(visible_items).enumerate() {
            let style = if i + state.offset == state.selected {
                Style::default().bg(Color::DarkGray)
            } else {
                Style::default()
            };
            buf.set_string(area.x, area.y + i as u16, item, style);
        }
    }
}
```

## Common Recipes

### Popup / Modal Dialog

```rust
fn draw_popup(frame: &mut Frame, title: &str, message: &str) {
    let area = frame.area().centered(Constraint::Percentage(60), Constraint::Percentage(20));
    frame.render_widget(Clear, area);
    let popup = Paragraph::new(message)
        .block(Block::bordered().title(title))
        .alignment(Alignment::Center)
        .wrap(Wrap { trim: true });
    frame.render_widget(popup, area);
}
```

### Input Field

```rust
struct App {
    input: String,
    cursor_position: usize,
    mode: InputMode,
}

enum InputMode { Normal, Editing }

// In draw:
let input = Paragraph::new(app.input.as_str())
    .block(Block::bordered().title("Input"));
frame.render_widget(input, area);

if matches!(app.mode, InputMode::Editing) {
    frame.set_cursor_position((
        area.x + app.cursor_position as u16 + 1, // +1 for border
        area.y + 1,
    ));
}

// In handle_key (Editing mode):
KeyCode::Char(c) => {
    app.input.insert(app.cursor_position, c);
    app.cursor_position += 1;
}
KeyCode::Backspace => {
    if app.cursor_position > 0 {
        app.cursor_position -= 1;
        app.input.remove(app.cursor_position);
    }
}
KeyCode::Left => { app.cursor_position = app.cursor_position.saturating_sub(1); }
KeyCode::Right => { app.cursor_position = (app.cursor_position + 1).min(app.input.len()); }
```

### Multiple Screens / Routing

```rust
enum Screen { Home, Settings, Help }

fn draw(frame: &mut Frame, app: &App) {
    match app.current_screen {
        Screen::Home => draw_home(frame, app),
        Screen::Settings => draw_settings(frame, app),
        Screen::Help => draw_help(frame, app),
    }
}
```

### Testing with TestBackend

```rust
#[cfg(test)]
mod tests {
    use ratatui::{backend::TestBackend, Terminal, buffer::Buffer};

    #[test]
    fn test_renders_correctly() {
        let backend = TestBackend::new(40, 10);
        let mut terminal = Terminal::new(backend).unwrap();

        terminal.draw(|frame| {
            ui(frame);
        }).unwrap();

        let expected = Buffer::with_lines(vec![
            "┌Title──────────────────────────────────┐",
            "│Hello, world!                          │",
            "└───────────────────────────────────────┘",
        ]);
        terminal.backend().assert_buffer(&expected);
    }
}
```
