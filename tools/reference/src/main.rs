//! Writes the tier 2 vector files: `cargo run --manifest-path tools/reference/Cargo.toml`.

use meshlink_reference::vectors::{DIRECTORY, all};

fn main() -> std::io::Result<()> {
    for document in all() {
        std::fs::write(format!("{DIRECTORY}/{}", document.name), document.vectors.to_pretty())?;
        std::fs::write(
            format!("{DIRECTORY}/{}", document.schema_name()),
            document.schema.to_pretty(),
        )?;
        println!("wrote {} and its schema", document.name);
    }
    Ok(())
}
