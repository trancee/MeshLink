//! A minimal JSON value with the layout of Python's `json.dump(indent=2)`, which also writes the
//! tier 1 files. Test builds add a parser for the vendored and tier 1 vector files.

use std::fmt::Write as _;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value {
    Bool(bool),
    Int(i64),
    Str(String),
    List(Vec<Value>),
    Object(Vec<(String, Value)>),
}

/// An object from key-value pairs, keeping their order.
#[must_use]
pub fn object(pairs: Vec<(&str, Value)>) -> Value {
    Value::Object(pairs.into_iter().map(|(key, value)| (key.to_owned(), value)).collect())
}

#[must_use]
pub fn hex(bytes: &[u8]) -> Value {
    Value::Str(to_hex(bytes))
}

#[must_use]
pub fn text(s: &str) -> Value {
    Value::Str(s.to_owned())
}

#[must_use]
pub fn int(n: impl Into<i64>) -> Value {
    Value::Int(n.into())
}

#[must_use]
pub fn texts(items: &[&str]) -> Value {
    Value::List(items.iter().map(|s| text(s)).collect())
}

#[must_use]
pub fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, b| {
        write!(out, "{b:02x}").expect("writing to a String cannot fail");
        out
    })
}

impl Value {
    /// The document as Python's `json.dump(value, indent=2)` writes it, plus a final newline.
    #[must_use]
    pub fn to_pretty(&self) -> String {
        let mut out = String::new();
        self.write(&mut out, 0);
        out.push('\n');
        out
    }

    fn write(&self, out: &mut String, depth: usize) {
        match self {
            Self::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            Self::Int(n) => out.push_str(&n.to_string()),
            Self::Str(s) => write_string(out, s),
            Self::List(items) if items.is_empty() => out.push_str("[]"),
            Self::Object(pairs) if pairs.is_empty() => out.push_str("{}"),
            Self::List(items) => {
                out.push('[');
                for (i, item) in items.iter().enumerate() {
                    separator(out, i, depth + 1);
                    item.write(out, depth + 1);
                }
                newline(out, depth);
                out.push(']');
            }
            Self::Object(pairs) => {
                out.push('{');
                for (i, (key, value)) in pairs.iter().enumerate() {
                    separator(out, i, depth + 1);
                    write_string(out, key);
                    out.push_str(": ");
                    value.write(out, depth + 1);
                }
                newline(out, depth);
                out.push('}');
            }
        }
    }
}

fn separator(out: &mut String, index: usize, depth: usize) {
    if index > 0 {
        out.push(',');
    }
    newline(out, depth);
}

fn newline(out: &mut String, depth: usize) {
    out.push('\n');
    out.push_str(&"  ".repeat(depth));
}

fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            ' '..='~' => out.push(c),
            _ => write!(out, "\\u{:04x}", u32::from(c)).expect("writing to a String cannot fail"),
        }
    }
    out.push('"');
}

#[cfg(test)]
pub mod parse {
    //! Enough JSON for the vector files: objects, arrays, strings without escapes beyond `\"`
    //! and `\\`, integers and booleans.

    use super::Value;

    pub fn parse(text: &str) -> Value {
        let mut parser = Parser {
            bytes: text.as_bytes(),
            pos: 0,
        };
        let value = parser.value();
        parser.skip_space();
        assert_eq!(parser.pos, parser.bytes.len(), "trailing JSON");
        value
    }

    struct Parser<'a> {
        bytes: &'a [u8],
        pos: usize,
    }

    impl Parser<'_> {
        fn skip_space(&mut self) {
            while self.pos < self.bytes.len() && self.bytes[self.pos].is_ascii_whitespace() {
                self.pos += 1;
            }
        }

        fn next(&mut self) -> u8 {
            self.skip_space();
            let b = self.bytes[self.pos];
            self.pos += 1;
            b
        }

        fn value(&mut self) -> Value {
            match self.next() {
                b'{' => self.object(),
                b'[' => self.list(),
                b'"' => Value::Str(self.string()),
                b't' => self.keyword("true", Value::Bool(true)),
                b'f' => self.keyword("false", Value::Bool(false)),
                _ => self.number(),
            }
        }

        /// Called after the keyword's first byte was consumed.
        fn keyword(&mut self, word: &str, value: Value) -> Value {
            assert!(
                self.bytes[self.pos - 1..].starts_with(word.as_bytes()),
                "bad JSON keyword"
            );
            self.pos += word.len() - 1;
            value
        }

        fn number(&mut self) -> Value {
            let start = self.pos - 1;
            while self.pos < self.bytes.len() && (self.bytes[self.pos] == b'-' || self.bytes[self.pos].is_ascii_digit())
            {
                self.pos += 1;
            }
            let digits = std::str::from_utf8(&self.bytes[start..self.pos]).expect("ASCII");
            Value::Int(digits.parse().expect("integer"))
        }

        fn string(&mut self) -> String {
            let mut out = Vec::new();
            loop {
                let b = self.bytes[self.pos];
                self.pos += 1;
                match b {
                    b'"' => return String::from_utf8(out).expect("UTF-8"),
                    b'\\' => {
                        out.push(self.bytes[self.pos]);
                        self.pos += 1;
                    }
                    _ => out.push(b),
                }
            }
        }

        fn list(&mut self) -> Value {
            let mut items = Vec::new();
            self.skip_space();
            if self.bytes[self.pos] == b']' {
                self.pos += 1;
                return Value::List(items);
            }
            loop {
                items.push(self.value());
                if self.next() == b']' {
                    return Value::List(items);
                }
            }
        }

        fn object(&mut self) -> Value {
            let mut pairs = Vec::new();
            self.skip_space();
            if self.bytes[self.pos] == b'}' {
                self.pos += 1;
                return Value::Object(pairs);
            }
            loop {
                assert_eq!(self.next(), b'"', "object key");
                let key = self.string();
                assert_eq!(self.next(), b':', "object colon");
                pairs.push((key, self.value()));
                if self.next() == b'}' {
                    return Value::Object(pairs);
                }
            }
        }
    }

    impl Value {
        pub fn get(&self, key: &str) -> &Value {
            match self {
                Value::Object(pairs) => {
                    &pairs
                        .iter()
                        .find(|(k, _)| k == key)
                        .unwrap_or_else(|| panic!("no {key}"))
                        .1
                }
                _ => panic!("not an object"),
            }
        }

        pub fn items(&self) -> &[Value] {
            match self {
                Value::List(items) => items,
                _ => panic!("not a list"),
            }
        }

        pub fn str(&self) -> &str {
            match self {
                Value::Str(s) => s,
                _ => panic!("not a string"),
            }
        }

        pub fn int(&self) -> i64 {
            match self {
                Value::Int(n) => *n,
                _ => panic!("not an integer"),
            }
        }

        pub fn bytes(&self) -> Vec<u8> {
            let s = self.str();
            (0..s.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
                .collect()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_matches_python_indent_2() {
        let doc = object(vec![
            ("a", int(1)),
            ("b", texts(&["x"])),
            ("c", Value::List(vec![])),
            ("d", object(vec![])),
        ]);
        assert_eq!(
            doc.to_pretty(),
            "{\n  \"a\": 1,\n  \"b\": [\n    \"x\"\n  ],\n  \"c\": [],\n  \"d\": {}\n}\n"
        );
    }

    #[test]
    fn parser_reads_what_the_writer_writes() {
        let doc = object(vec![(
            "k",
            Value::List(vec![int(-3), Value::Bool(true), text("q\"\\")]),
        )]);
        assert_eq!(parse::parse(&doc.to_pretty()), doc);
    }
}
