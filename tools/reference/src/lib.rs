//! MeshLink's test-only Rust reference (ticket 20, ADR 0002 and ADR 0005).
//!
//! It is the second, independent implementation behind the tier 2 vectors in
//! `docs/spec/vectors/`. Nothing here is published or linked into an artifact.

pub mod broadcast;
pub mod certificate;
pub mod handshake;
pub mod json;
pub mod seal;
pub mod spec;
pub mod vectors;
