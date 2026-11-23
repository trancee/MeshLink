//! The tier 2 vector files and their schemas, in the vectors README format.

use crate::broadcast::{self, Header};
use crate::certificate::{self, CLASSICAL, CLOCK_SKEW, Identity, POST_QUANTUM, VALIDITY_MAX};
use crate::handshake::{self, KemInput, Rejected, Side};
use crate::json::{Value, hex, int, object, text, texts};
use crate::lookup;
use crate::seal;
use crate::spec::{e2e_prologue, flip, hpke_info, link_prologue, mesh_id_digest, mesh_psk, seed, seed64, tlv};

/// Where the files live, relative to this crate.
pub const DIRECTORY: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../docs/spec/vectors");

const NOT_BEFORE: u32 = 1_798_761_600;
const NOT_AFTER: u32 = NOT_BEFORE + VALIDITY_MAX;
const NOW: u32 = NOT_BEFORE + 3_600;
const MESH_ID: &str = "reference-mesh";
const XX: &str = "Noise_XX_25519_ChaChaPoly_SHA256";
const XXPSK3: &str = "Noise_XXpsk3_25519_ChaChaPoly_SHA256";
const XXHFS: &str = "Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256";
const XXHFSPSK3: &str = "Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256";
const IK: &str = "Noise_IK_25519_ChaChaPoly_SHA256";
const IKHFS: &str = "Noise_IKhfs_25519+MLKEM768_ChaChaPoly_SHA256";
const RESOLVING_KEY: u8 = 0x02;
const CERTIFICATE: u8 = 0x81;
const SENDER_CERTIFICATE: u8 = 0x01;
const CONVERSATION_SEED: u8 = 0x82;
const MESSAGE_IDS: u8 = 0x83;
const PADDING_BLOCK: usize = 32;

/// One generated file: its name and the vector and schema documents.
pub struct Document {
    pub name: &'static str,
    pub vectors: Value,
    pub schema: Value,
}

impl Document {
    #[must_use]
    pub fn schema_name(&self) -> String {
        self.name.replace(".json", ".schema.json")
    }
}

#[must_use]
pub fn all() -> Vec<Document> {
    vec![
        broadcast_signatures(),
        certificate_signatures(),
        handshake_xx(),
        handshake_hfs(),
        first_message(),
        e2e_sessions(),
        hpke_seal(),
        link_transport(),
        lookup_reply(),
    ]
}

struct Test {
    comment: String,
    flags: Vec<&'static str>,
    fields: Vec<(&'static str, Value)>,
    error: Option<&'static str>,
}

fn test(
    comment: impl Into<String>,
    flags: &[&'static str],
    fields: Vec<(&'static str, Value)>,
    error: Option<&'static str>,
) -> Test {
    Test {
        comment: comment.into(),
        flags: flags.to_vec(),
        fields,
        error,
    }
}

struct Group {
    attributes: Vec<(&'static str, Value)>,
    tests: Vec<Test>,
}

fn document(
    name: &'static str,
    algorithm: &str,
    notes: &[(&str, &str)],
    groups: Vec<Group>,
    test_properties: Vec<(&str, Value)>,
    group_properties: Vec<(&str, Value)>,
) -> Document {
    let schema_name = name.replace(".json", ".schema.json");
    let mut count = 0;
    let groups = groups
        .into_iter()
        .map(|group| {
            let tests = group
                .tests
                .into_iter()
                .map(|t| {
                    count += 1;
                    let mut pairs = vec![
                        ("tcId", int(count)),
                        ("comment", text(&t.comment)),
                        ("flags", texts(&t.flags)),
                        ("result", text(if t.error.is_some() { "invalid" } else { "valid" })),
                    ];
                    pairs.extend(t.fields);
                    if let Some(error) = t.error {
                        pairs.push(("error", text(error)));
                    }
                    object(pairs)
                })
                .collect();
            let mut pairs = group.attributes;
            pairs.push(("tests", Value::List(tests)));
            object(pairs)
        })
        .collect();
    let vectors = object(vec![
        ("$schema", text(&format!("./{schema_name}"))),
        ("tier", int(2)),
        ("algorithm", text(algorithm)),
        ("schema", text(&schema_name)),
        ("generatorVersion", text("1")),
        ("numberOfTests", int(count)),
        ("notes", object(notes.iter().map(|(k, v)| (*k, text(v))).collect())),
        ("testGroups", Value::List(groups)),
    ]);
    let schema = vector_schema(name, &schema_name, test_properties, group_properties);
    Document { name, vectors, schema }
}

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------

fn s_hex() -> Value {
    object(vec![("type", text("string")), ("pattern", text("^([0-9a-f]{2})*$"))])
}

fn s_hex_of(length: usize) -> Value {
    object(vec![
        ("type", text("string")),
        ("pattern", text(&format!("^[0-9a-f]{{{}}}$", length * 2))),
    ])
}

fn s_type(name: &str) -> Value {
    object(vec![("type", text(name))])
}

fn s_integer(minimum: i64) -> Value {
    object(vec![("type", text("integer")), ("minimum", int(minimum))])
}

fn s_const(value: Value) -> Value {
    object(vec![("const", value)])
}

fn s_enum(values: &[&str]) -> Value {
    object(vec![("enum", texts(values))])
}

fn s_list(items: Value, min_items: i64) -> Value {
    object(vec![
        ("type", text("array")),
        ("minItems", int(min_items)),
        ("items", items),
    ])
}

fn s_object(required: &[&str], properties: Vec<(&str, Value)>) -> Value {
    object(vec![
        ("type", text("object")),
        ("required", texts(required)),
        ("properties", object(properties)),
        ("additionalProperties", Value::Bool(false)),
    ])
}

fn keys(properties: &[(&str, Value)], optional: &[&str]) -> Vec<&'static str> {
    properties
        .iter()
        .map(|(k, _)| *k)
        .filter(|k| !optional.contains(k))
        .map(|k| Box::leak(k.to_owned().into_boxed_str()) as &'static str)
        .collect()
}

fn vector_schema(
    name: &str,
    schema_name: &str,
    test_properties: Vec<(&str, Value)>,
    group_properties: Vec<(&str, Value)>,
) -> Value {
    let mut tests = vec![
        ("tcId", s_integer(1)),
        ("comment", s_type("string")),
        (
            "flags",
            object(vec![("type", text("array")), ("items", s_type("string"))]),
        ),
        ("result", s_enum(&["valid", "invalid"])),
    ];
    let optional: Vec<&str> = test_properties
        .iter()
        .filter(|(k, _)| k.ends_with('?'))
        .map(|(k, _)| *k)
        .collect();
    let test_properties: Vec<(&str, Value)> = test_properties
        .into_iter()
        .map(|(k, v)| (k.trim_end_matches('?'), v))
        .collect();
    tests.extend(test_properties);
    tests.push(("error", s_type("string")));
    let optional: Vec<&str> = optional
        .iter()
        .map(|k| k.trim_end_matches('?'))
        .chain(["error"])
        .collect();
    let test = s_object(&keys(&tests, &optional), tests);
    let mut groups = group_properties;
    groups.push(("tests", s_list(test, 1)));
    let group = s_object(&keys(&groups, &[]), groups);
    object(vec![
        ("$schema", text("https://json-schema.org/draft/2020-12/schema")),
        ("$id", text(schema_name)),
        ("title", text(&format!("MeshLink protocol 1 test vectors: {name}"))),
        ("type", text("object")),
        (
            "required",
            texts(&[
                "tier",
                "algorithm",
                "schema",
                "generatorVersion",
                "numberOfTests",
                "notes",
                "testGroups",
            ]),
        ),
        (
            "properties",
            object(vec![
                ("$schema", s_type("string")),
                ("tier", s_const(int(2))),
                ("algorithm", s_type("string")),
                ("schema", s_const(text(schema_name))),
                ("generatorVersion", s_type("string")),
                ("numberOfTests", s_integer(1)),
                ("notes", s_type("object")),
                ("testGroups", s_list(group, 1)),
            ]),
        ),
        ("additionalProperties", Value::Bool(false)),
    ])
}

// ---------------------------------------------------------------------------
// Signed broadcasts (ticket 39a)
// ---------------------------------------------------------------------------

fn broadcast_signatures() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    document(
        "broadcast-signatures.json",
        "MeshLink/1 signed broadcast",
        &[
            (
                "ReferenceKeys",
                "Keys come from fixed labels in tools/reference. ed25519_seed is the RFC 8032 private key that signed the frame's signing input; it is absent when no single key signed the bytes shown.",
            ),
            (
                "HopLimit",
                "A relay lowers hop_limit; the signature leaves that byte out, so it stays valid (routing.md, BROADCAST).",
            ),
            (
                "Tampered",
                "Bytes changed after signing, another origin_key, or a signature by another identity key.",
            ),
        ],
        vec![Group {
            attributes: vec![("type", text("BroadcastVerify"))],
            tests: broadcast_tests(&alice, &bob),
        }],
        vec![
            ("frame", s_hex()),
            ("node_id", s_hex_of(32)),
            ("signing_input", s_hex()),
            ("ed25519_seed?", s_hex_of(32)),
        ],
        vec![("type", s_const(text("BroadcastVerify")))],
    )
}

fn broadcast_tests(alice: &Identity, bob: &Identity) -> Vec<Test> {
    let header = Header {
        hop_limit: 64,
        message_id: seed("broadcast message id")[..12].try_into().expect("12 B"),
        expiry: NOW + 600,
    };
    let payload = b"Water at gate 3";
    let good = broadcast::sign(header, &alice.signing, &alice.signing, payload);
    let at_hop =
        |hop_limit: u8| broadcast::sign(Header { hop_limit, ..header }, &alice.signing, &alice.signing, payload);
    let mut lowered = good.clone();
    lowered[1] = 63;
    let mut swapped = good.clone();
    swapped[19..51].copy_from_slice(&bob.ed25519_key());
    let largest = vec![0x5a; broadcast::PAYLOAD_MAX];
    let signature = good.len() - 64;
    let hop = &["HopLimit"];
    let tampered = &["Tampered"];
    let case = |comment: &str, flags: &[&'static str], frame: Vec<u8>, signer: Option<&Identity>| {
        let result = broadcast::verify(&frame);
        let mut fields = vec![
            ("frame", hex(&frame)),
            ("node_id", hex(&crate::spec::node_id(&broadcast::origin_key(&frame)))),
            ("signing_input", hex(&broadcast::signing_input(&frame))),
        ];
        if let Some(signer) = signer {
            fields.push(("ed25519_seed", hex(signer.signing.as_bytes())));
        }
        test(comment, flags, fields, result.err())
    };
    vec![
        case("valid, hop limit 64", &[], good.clone(), Some(alice)),
        case("hop limit lowered to 63 by a relay", hop, lowered, Some(alice)),
        case("signed at hop limit 1", hop, at_hop(1), Some(alice)),
        case("signed at hop limit 255", hop, at_hop(255), Some(alice)),
        case(
            "empty payload",
            &[],
            broadcast::sign(header, &alice.signing, &alice.signing, b""),
            Some(alice),
        ),
        case(
            "1,024 B payload",
            &[],
            broadcast::sign(header, &alice.signing, &alice.signing, &largest),
            Some(alice),
        ),
        case("flipped payload bit", tampered, flip(&good, 18 + 35), None),
        case("flipped signature bit", tampered, flip(&good, signature), None),
        case("flipped message_id bit", tampered, flip(&good, 2), None),
        case("flipped expiry bit", tampered, flip(&good, 17), None),
        case("origin_key swapped for another identity's", tampered, swapped, None),
        case(
            "signed by another identity key",
            tampered,
            broadcast::sign(header, &alice.signing, &bob.signing, payload),
            Some(bob),
        ),
    ]
}

// ---------------------------------------------------------------------------
// Certificate signatures (ticket 21)
// ---------------------------------------------------------------------------

fn certificate_signatures() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let groups = [CLASSICAL, POST_QUANTUM].map(|profile| Group {
        attributes: vec![
            ("type", text("CertificateVerify")),
            ("profile", text(&format!("0x{profile:02x}"))),
        ],
        tests: certificate_tests(profile, &alice, &bob),
    });
    document(
        "certificate-signatures.json",
        "MeshLink/1 identity certificate signature and validity",
        &[
            (
                "ReferenceKeys",
                "Keys come from fixed labels in tools/reference. ed25519_seed is the RFC 8032 private key that signed a test certificate; it is absent when no single key signed the bytes shown.",
            ),
            (
                "Window",
                "Tests at the edges of the validity window and the 30 s clock skew (identity.md, Validation, rules 2, 5 and 6).",
            ),
            (
                "Tampered",
                "Bytes changed after signing, a signature by another key, or a NodeId that isn't the identity key's.",
            ),
            (
                "Layout",
                "A certificate of another profile or another length; the layout rules run before the signature, and the test has no signing_input.",
            ),
        ],
        Vec::from(groups),
        vec![
            ("certificate", s_hex()),
            ("node_id", s_hex_of(32)),
            ("now", s_integer(0)),
            ("signing_input?", s_hex()),
            ("ed25519_seed?", s_hex_of(32)),
        ],
        vec![
            ("type", s_const(text("CertificateVerify"))),
            ("profile", s_enum(&["0x01", "0x02"])),
        ],
    )
}

struct CertificateCase<'a> {
    comment: &'static str,
    flags: &'static [&'static str],
    bytes: Vec<u8>,
    node_id: [u8; 32],
    now: u32,
    signer: Option<&'a Identity>,
    error: Option<&'static str>,
}

impl<'a> CertificateCase<'a> {
    /// A case for `alice`'s NodeId at `NOW`, signed by `signer`.
    fn new(
        comment: &'static str,
        flags: &'static [&'static str],
        bytes: Vec<u8>,
        signer: Option<&'a Identity>,
    ) -> Self {
        let node_id = Identity::new("alice").node_id();
        Self {
            comment,
            flags,
            bytes,
            node_id,
            now: NOW,
            signer,
            error: None,
        }
    }

    fn now(self, now: u32) -> Self {
        Self { now, ..self }
    }

    fn node_id(self, node_id: [u8; 32]) -> Self {
        Self { node_id, ..self }
    }

    fn error(self, error: &'static str) -> Self {
        Self {
            error: Some(error),
            ..self
        }
    }

    fn into_test(self, profile: u8) -> Test {
        let result = certificate::verify(&self.bytes, profile, &self.node_id, self.now);
        assert_eq!(result.err(), self.error, "{}", self.comment);
        let mut fields = vec![
            ("certificate", hex(&self.bytes)),
            ("node_id", hex(&self.node_id)),
            ("now", int(self.now)),
        ];
        if !self.flags.contains(&"Layout") {
            fields.push(("signing_input", hex(&certificate::signing_input(&self.bytes))));
        }
        if let Some(signer) = self.signer {
            fields.push(("ed25519_seed", hex(signer.signing.as_bytes())));
        }
        test(self.comment, self.flags, fields, self.error)
    }
}

fn certificate_tests(profile: u8, alice: &Identity, bob: &Identity) -> Vec<Test> {
    let other = if profile == CLASSICAL { POST_QUANTUM } else { CLASSICAL };
    let good = alice.certificate(profile, NOT_BEFORE, NOT_AFTER);
    let by_bob = certificate::sign(&alice.body(profile, NOT_BEFORE, NOT_AFTER), &bob.signing);
    let mut trailing = good.clone();
    trailing.push(0);
    let case = CertificateCase::new;
    let window = &["Window"];
    let tampered = &["Tampered"];
    let layout = &["Layout"];
    let wide = alice.certificate(profile, NOT_BEFORE, NOT_AFTER + 1);
    let empty = alice.certificate(profile, NOT_BEFORE, NOT_BEFORE);
    [
        case("valid, one hour after not_before", &[], good.clone(), Some(alice)),
        case(
            "now = not_before - 30 s, inside the skew",
            window,
            good.clone(),
            Some(alice),
        )
        .now(NOT_BEFORE - CLOCK_SKEW),
        case("now = not_before - 31 s", window, good.clone(), Some(alice))
            .now(NOT_BEFORE - CLOCK_SKEW - 1)
            .error("not-yet-valid"),
        case(
            "now = not_after + 29 s, inside the skew",
            window,
            good.clone(),
            Some(alice),
        )
        .now(NOT_AFTER + CLOCK_SKEW - 1),
        case("now = not_after + 30 s", window, good.clone(), Some(alice))
            .now(NOT_AFTER + CLOCK_SKEW)
            .error("expired"),
        case("validity window 86,401 s", window, wide, Some(alice)).error("out-of-range"),
        case("validity window 0 s", window, empty, Some(alice)).error("out-of-range"),
        case("flipped signature bit", tampered, flip(&good, good.len() - 64), None).error("bad-signature"),
        case("signed by another identity key", tampered, by_bob, Some(bob)).error("bad-signature"),
        case("x25519_key changed after signing", tampered, flip(&good, 40), None).error("bad-signature"),
        case("NodeId of another identity", tampered, good.clone(), Some(alice))
            .node_id(bob.node_id())
            .error("node-id-mismatch"),
        case(
            "certificate of the other profile",
            layout,
            alice.certificate(other, NOT_BEFORE, NOT_AFTER),
            Some(alice),
        )
        .error("unknown-kind"),
        case(
            "last signature byte missing",
            layout,
            good[..good.len() - 1].to_vec(),
            None,
        )
        .error("truncated"),
        case("one trailing byte", layout, trailing, None).error("trailing-bytes"),
    ]
    .into_iter()
    .map(|c| c.into_test(profile))
    .collect()
}

// ---------------------------------------------------------------------------
// XXhfs and XXhfs+psk3 handshakes (ticket 23)
// ---------------------------------------------------------------------------

fn handshake_hfs() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let digest = mesh_id_digest(MESH_ID, POST_QUANTUM);
    let prologue = link_prologue(&[1], 1, POST_QUANTUM, &digest);
    let other_prologue = link_prologue(&[1, 2], 1, POST_QUANTUM, &digest);
    let mesh_key = seed("mesh key");
    let wrong_key = seed("another mesh key");
    let setup = |protocol, responder_prologue, mesh_keys| Setup {
        protocol,
        profile: POST_QUANTUM,
        alice: &alice,
        bob: &bob,
        prologues: (&prologue, responder_prologue),
        mesh_keys,
    };
    let open = |tamper, responder_prologue, comment: &str, flags: &[&'static str]| {
        handshake_test(setup(XXHFS, responder_prologue, None), tamper, comment, flags)
    };
    let gated = |tamper, responder_key, comment: &str, flags: &[&'static str]| {
        handshake_test(
            setup(XXHFSPSK3, &prologue, Some((&mesh_key, responder_key))),
            tamper,
            comment,
            flags,
        )
    };
    let groups = vec![
        Group {
            attributes: vec![
                ("type", text("NoiseHandshake")),
                ("protocol", text(XXHFS)),
                ("gated", Value::Bool(false)),
            ],
            tests: vec![
                open(None, &prologue, "open mesh, post-quantum profile", &[]),
                open(
                    Some((1, 32 + 100)),
                    &prologue,
                    "e1 KEM public key changed in message 1",
                    &["Tampered"],
                ),
                open(
                    Some((2, 32 + 100)),
                    &prologue,
                    "KEM ciphertext changed in message 2",
                    &["Tampered"],
                ),
                open(
                    Some((3, 10)),
                    &prologue,
                    "encrypted static key changed in message 3",
                    &["Tampered"],
                ),
                open(
                    None,
                    &other_prologue,
                    "responder read a different capability record",
                    &["Prologue"],
                ),
            ],
        },
        Group {
            attributes: vec![
                ("type", text("NoiseHandshake")),
                ("protocol", text(XXHFSPSK3)),
                ("gated", Value::Bool(true)),
            ],
            tests: vec![
                gated(None, &mesh_key, "gated mesh, post-quantum profile", &[]),
                gated(None, &wrong_key, "responder has another mesh key", &["Psk"]),
            ],
        },
    ];
    handshake_document(
        "handshake-hfs.json",
        "MeshLink/1 post-quantum link handshake (Noise XXhfs, XXhfs+psk3)",
        "Keys, ephemerals and KEM inputs come from fixed labels in tools/reference. Private keys are the raw 32 B X25519 inputs. kem_keygen_seed is the FIPS 203 ML-KEM.KeyGen_internal seed d ‖ z; kem_encaps_seed is the ML-KEM.Encaps_internal message m.",
        groups,
        [XXHFS, XXHFSPSK3],
        Some([("kem_keygen_seed", 64), ("kem_encaps_seed", 32)]),
    )
}

// ---------------------------------------------------------------------------
// XX and XXpsk3 handshakes (ticket 24)
// ---------------------------------------------------------------------------

fn handshake_xx() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let digest = mesh_id_digest(MESH_ID, CLASSICAL);
    let prologue = link_prologue(&[1], 1, CLASSICAL, &digest);
    let other_prologue = link_prologue(&[1, 2], 1, CLASSICAL, &digest);
    let mesh_key = seed("mesh key");
    let wrong_key = seed("another mesh key");
    let setup = |protocol, responder_prologue, mesh_keys| Setup {
        protocol,
        profile: CLASSICAL,
        alice: &alice,
        bob: &bob,
        prologues: (&prologue, responder_prologue),
        mesh_keys,
    };
    let open = |tamper, responder_prologue, comment: &str, flags: &[&'static str]| {
        handshake_test(setup(XX, responder_prologue, None), tamper, comment, flags)
    };
    let gated = |tamper, responder_key, comment: &str, flags: &[&'static str]| {
        handshake_test(
            setup(XXPSK3, &prologue, Some((&mesh_key, responder_key))),
            tamper,
            comment,
            flags,
        )
    };
    let groups = vec![
        Group {
            attributes: vec![
                ("type", text("NoiseHandshake")),
                ("protocol", text(XX)),
                ("gated", Value::Bool(false)),
            ],
            tests: vec![
                open(None, &prologue, "open mesh, classical profile", &[]),
                open(Some((1, 10)), &prologue, "e changed in message 1", &["Tampered"]),
                open(
                    Some((2, 32 + 10)),
                    &prologue,
                    "encrypted static key changed in message 2",
                    &["Tampered"],
                ),
                open(
                    Some((3, 10)),
                    &prologue,
                    "encrypted static key changed in message 3",
                    &["Tampered"],
                ),
                open(
                    None,
                    &other_prologue,
                    "responder read a different capability record",
                    &["Prologue"],
                ),
            ],
        },
        Group {
            attributes: vec![
                ("type", text("NoiseHandshake")),
                ("protocol", text(XXPSK3)),
                ("gated", Value::Bool(true)),
            ],
            tests: vec![
                gated(None, &mesh_key, "gated mesh, classical profile", &[]),
                gated(None, &wrong_key, "responder has another mesh key", &["Psk"]),
            ],
        },
    ];
    handshake_document(
        "handshake-xx.json",
        "MeshLink/1 classical link handshake (Noise XX, XXpsk3)",
        "Keys and ephemerals come from fixed labels in tools/reference. Private keys are the raw 32 B X25519 inputs.",
        groups,
        [XX, XXPSK3],
        None,
    )
}

/// A link handshake file: `protocols` are the open and the gated pattern; `kems` name the
/// initiator's and the responder's fixed KEM input and its length, if the pattern has a KEM.
fn handshake_document(
    name: &'static str,
    algorithm: &str,
    reference_keys: &str,
    groups: Vec<Group>,
    protocols: [&str; 2],
    kems: Option<[(&'static str, usize); 2]>,
) -> Document {
    let transport = s_object(
        &["direction", "plaintext", "ciphertext"],
        vec![
            (
                "direction",
                s_enum(&["initiator-to-responder", "responder-to-initiator"]),
            ),
            ("plaintext", s_hex()),
            ("ciphertext", s_hex()),
        ],
    );
    document(
        name,
        algorithm,
        &[
            ("ReferenceKeys", reference_keys),
            (
                "Replay",
                "Each side writes its message from its inputs and the payload. messages are the bytes as delivered: a writer's own output equals them except at tampered_step. The reader of rejected_step must fail the handshake; every earlier message must succeed.",
            ),
            ("Tampered", "One bit flipped in transit at tampered_step."),
            (
                "Prologue",
                "The two sides hold different prologues, so message 2 fails.",
            ),
            ("Psk", "The two sides hold different mesh keys, so message 3 fails."),
        ],
        groups,
        vec![
            ("now", s_integer(0)),
            ("initiator", side_schema(kems.map(|k| k[0]))),
            ("responder", side_schema(kems.map(|k| k[1]))),
            ("initiator_node_id", s_hex_of(32)),
            ("responder_node_id", s_hex_of(32)),
            ("payloads", s_list(s_hex(), 3)),
            ("messages", s_list(s_hex(), 1)),
            ("tampered_step?", s_integer(1)),
            ("rejected_step?", s_integer(1)),
            ("handshake_hash?", s_hex_of(32)),
            ("transport?", s_list(transport, 2)),
        ],
        vec![
            ("type", s_const(text("NoiseHandshake"))),
            ("protocol", s_enum(&protocols)),
            ("gated", s_type("boolean")),
        ],
    )
}

/// The inputs shared by the tests of one handshake: the initiator is `alice`, the responder `bob`.
#[derive(Clone, Copy)]
struct Setup<'a> {
    protocol: &'static str,
    profile: u8,
    alice: &'a Identity,
    bob: &'a Identity,
    prologues: (&'a [u8], &'a [u8]),
    mesh_keys: Option<(&'a [u8; 32], &'a [u8; 32])>,
}

/// The payloads each side writes and both sides' inputs.
fn sides(setup: Setup) -> ([Vec<u8>; 3], Side, Side) {
    let Setup {
        profile,
        alice,
        bob,
        prologues,
        mesh_keys,
        ..
    } = setup;
    let gated = mesh_keys.is_some();
    let payload = |who: &Identity, label: &str| {
        let mut out = Vec::new();
        if gated {
            let mut value = seed(&format!("{label} resolving key")).to_vec();
            value.extend_from_slice(&NOT_AFTER.to_be_bytes());
            out.extend(tlv(RESOLVING_KEY, &value));
        }
        out.extend(tlv(CERTIFICATE, &who.certificate(profile, NOT_BEFORE, NOT_AFTER)));
        out
    };
    let payloads = [Vec::new(), payload(bob, "bob"), payload(alice, "alice")];
    let post_quantum = profile == POST_QUANTUM;
    let make = |who: &Identity, label: &str, kem: KemInput, prologue: &[u8], mesh_key: Option<&[u8; 32]>| Side {
        static_private: who.x25519_private,
        ephemeral_private: seed(&format!("{label} handshake ephemeral")),
        kem: post_quantum.then_some(kem),
        prologue: prologue.to_vec(),
        psk: mesh_key.map(|key| mesh_psk(key, &prologue[prologue.len() - 32..])),
    };
    let initiator = make(
        alice,
        "alice",
        KemInput::Seed(seed64("alice handshake kem")),
        prologues.0,
        mesh_keys.map(|k| k.0),
    );
    let responder = make(
        bob,
        "bob",
        KemInput::Message(seed("bob handshake kem")),
        prologues.1,
        mesh_keys.map(|k| k.1),
    );
    (payloads, initiator, responder)
}

/// One side's inputs as a vector field: `kem_name` names its KEM input.
fn side_json(side: &Side, kem_name: &'static str, mesh_key: Option<&[u8; 32]>) -> Value {
    let mut pairs = vec![
        ("static_private", hex(&side.static_private)),
        ("ephemeral_private", hex(&side.ephemeral_private)),
    ];
    if let Some(kem) = side.kem {
        pairs.push((kem_name, hex(&kem.bytes())));
    }
    pairs.push(("prologue", hex(&side.prologue)));
    if let (Some(key), Some(psk)) = (mesh_key, side.psk) {
        pairs.push(("mesh_key", hex(key)));
        pairs.push(("psk", hex(&psk)));
    }
    object(pairs)
}

/// The schema of one side's inputs; `kem` names its KEM input and that input's length.
fn side_schema(kem: Option<(&'static str, usize)>) -> Value {
    let mut required = vec!["static_private", "ephemeral_private"];
    let mut properties = vec![("static_private", s_hex_of(32)), ("ephemeral_private", s_hex_of(32))];
    if let Some((field, length)) = kem {
        required.push(field);
        properties.push((field, s_hex_of(length)));
    }
    required.push("prologue");
    properties.extend([("prologue", s_hex()), ("mesh_key", s_hex_of(32)), ("psk", s_hex_of(32))]);
    s_object(&required, properties)
}

fn handshake_test(setup: Setup, tamper: Option<(usize, usize)>, comment: &str, flags: &[&'static str]) -> Test {
    let Setup {
        protocol,
        alice,
        bob,
        prologues,
        mesh_keys,
        ..
    } = setup;
    let (payloads, initiator, responder) = sides(setup);
    let transcript = handshake::run(protocol, &initiator, &responder, &payloads, tamper);
    let mut fields = vec![
        ("now", int(NOW)),
        (
            "initiator",
            side_json(&initiator, "kem_keygen_seed", mesh_keys.map(|k| k.0)),
        ),
        (
            "responder",
            side_json(&responder, "kem_encaps_seed", mesh_keys.map(|k| k.1)),
        ),
        ("initiator_node_id", hex(&alice.node_id())),
        ("responder_node_id", hex(&bob.node_id())),
        ("payloads", Value::List(payloads.iter().map(|p| hex(p)).collect())),
        (
            "messages",
            Value::List(transcript.messages.iter().map(|m| hex(m)).collect()),
        ),
    ];
    if let Some((step, _)) = tamper {
        fields.push(("tampered_step", int(i64::try_from(step).expect("step"))));
    }
    let error = match transcript.outcome {
        Ok(completed) => {
            assert!(
                tamper.is_none() && prologues.0 == prologues.1,
                "{comment}: expected a rejection"
            );
            fields.push(("handshake_hash", hex(&completed.handshake_hash)));
            let transport = completed.transport.iter().map(|t| {
                let direction = if t.from_initiator {
                    "initiator-to-responder"
                } else {
                    "responder-to-initiator"
                };
                object(vec![
                    ("direction", text(direction)),
                    ("plaintext", hex(&t.plaintext)),
                    ("ciphertext", hex(&t.ciphertext)),
                ])
            });
            fields.push(("transport", Value::List(transport.collect())));
            None
        }
        Err(Rejected(step)) => {
            fields.push(("rejected_step", int(i64::try_from(step).expect("step"))));
            Some("handshake-failed")
        }
    };
    test(comment, flags, fields, error)
}

// ---------------------------------------------------------------------------
// First messages (ticket 45a)
// ---------------------------------------------------------------------------

/// A secured `MESSAGE` plaintext from `alice`, padded: her `profile` certificate and a
/// conversation seed in its extensions, as every `IK_INITIATOR` plaintext carries.
fn first_plaintext(alice: &Identity, profile: u8) -> Vec<u8> {
    let mut extensions = tlv(SENDER_CERTIFICATE, &alice.certificate(profile, NOT_BEFORE, NOT_AFTER));
    extensions.extend(tlv(CONVERSATION_SEED, &seed("alice conversation seed")));
    let mut content = seed("first message id")[..12].to_vec();
    content.extend_from_slice(&(NOW + 3_600).to_be_bytes());
    content.extend_from_slice(&seed("first message k")[..16]);
    content.extend_from_slice(&u16::try_from(extensions.len()).expect("extensions").to_be_bytes());
    content.extend(extensions);
    content.extend_from_slice(b"hello");
    let mut out = u32::try_from(content.len()).expect("content").to_be_bytes().to_vec();
    out.extend(content);
    out.resize(out.len().next_multiple_of(PADDING_BLOCK), 0);
    out
}

/// How a first-message test departs from the valid run.
#[derive(Clone, Copy)]
enum Change {
    None,
    /// The initiator holds carol's static key instead of bob's.
    WrongResponderKey,
    /// This byte of the HPKE seal flipped before the initiator writes it.
    Seal(usize),
    /// This byte of message 1 flipped in transit.
    Transit(usize),
}

fn first_message_test(profile: u8, change: Change, comment: &str, flags: &[&'static str]) -> Test {
    let (alice, bob, carol) = (Identity::new("alice"), Identity::new("bob"), Identity::new("carol"));
    let post_quantum = profile == POST_QUANTUM;
    let (protocol, version) = (if post_quantum { IKHFS } else { IK }, 1);
    let prologue = e2e_prologue(version, profile);
    let initiator = Side {
        static_private: alice.x25519_private,
        ephemeral_private: seed("alice first message ephemeral"),
        kem: post_quantum.then_some(KemInput::Seed(seed64("alice first message kem"))),
        prologue: prologue.clone(),
        psk: None,
    };
    // The responder encapsulates only in message 2, so its KEM input never shows here.
    let responder = Side {
        static_private: bob.x25519_private,
        ephemeral_private: seed("bob first message ephemeral"),
        kem: post_quantum.then_some(KemInput::Message(seed("bob first message kem"))),
        prologue: prologue.clone(),
        psk: None,
    };
    let responder_key = if matches!(change, Change::WrongResponderKey) {
        carol.x25519_key()
    } else {
        bob.x25519_key()
    };
    let plaintext = first_plaintext(&alice, profile);
    let info = hpke_info(&alice.x25519_key(), &bob.node_id());
    let randomness = seed64("alice first message seal");
    let mut payload = if post_quantum {
        seal::seal(&bob.xwing_key(), &info, &plaintext, &randomness)
    } else {
        plaintext.clone()
    };
    if let Change::Seal(byte) = change {
        payload = flip(&payload, byte);
    }
    let tamper = if let Change::Transit(byte) = change {
        Some(byte)
    } else {
        None
    };
    let (message, read) = handshake::first_message(protocol, &initiator, &responder_key, &responder, &payload, tamper);
    let opened = read.as_ref().and_then(|read| {
        assert_eq!(read, &payload, "{comment}");
        if post_quantum {
            seal::open(&bob.xwing_ikm, &info, read)
        } else {
            Some(read.clone())
        }
    });
    let error = match (&read, &opened) {
        (None, _) => Some("handshake-failed"),
        (Some(_), None) => Some("open-failed"),
        (Some(_), Some(opened)) => {
            assert_eq!(opened, &plaintext, "{comment}");
            None
        }
    };
    let expected = match change {
        Change::None => None,
        Change::Seal(_) => Some("open-failed"),
        Change::WrongResponderKey | Change::Transit(_) => Some("handshake-failed"),
    };
    assert_eq!(error, expected, "{comment}");
    let mut initiator_fields = vec![
        ("static_private", hex(&initiator.static_private)),
        ("ephemeral_private", hex(&initiator.ephemeral_private)),
    ];
    let mut responder_fields = vec![("static_private", hex(&responder.static_private))];
    if post_quantum {
        initiator_fields.push(("kem_keygen_seed", hex(&initiator.kem.expect("kem").bytes())));
        responder_fields.push(("xwing_secret_key", hex(&bob.xwing_secret_key())));
    }
    initiator_fields.push(("prologue", hex(&prologue)));
    responder_fields.push(("prologue", hex(&prologue)));
    let mut fields = vec![
        ("now", int(NOW)),
        ("initiator", object(initiator_fields)),
        ("responder", object(responder_fields)),
        ("responder_key", hex(&responder_key)),
        ("initiator_node_id", hex(&alice.node_id())),
        ("responder_node_id", hex(&bob.node_id())),
        ("plaintext", hex(&plaintext)),
    ];
    if post_quantum {
        fields.push(("info", hex(&info)));
        fields.push(("encapsulation_randomness", hex(&randomness)));
    }
    fields.push(("payload", hex(&payload)));
    fields.push(("message", hex(&message)));
    test(comment, flags, fields, error)
}

fn first_message() -> Document {
    let last = |profile: u8| {
        let seal = if profile == POST_QUANTUM {
            seal::ENC_LENGTH + 16
        } else {
            0
        };
        let kem = if profile == POST_QUANTUM { 1_184 + 16 } else { 0 };
        32 + kem + 48 + first_plaintext(&Identity::new("alice"), profile).len() + seal + 16 - 1
    };
    let group = |protocol: &str, profile: u8, tests: Vec<Test>| Group {
        attributes: vec![
            ("type", text("FirstMessage")),
            ("protocol", text(protocol)),
            ("profile", int(profile)),
        ],
        tests,
    };
    let groups = vec![
        group(
            IK,
            CLASSICAL,
            vec![
                first_message_test(CLASSICAL, Change::None, "classical first message", &[]),
                first_message_test(
                    CLASSICAL,
                    Change::Transit(last(CLASSICAL)),
                    "payload tag bit flipped in transit",
                    &["Tampered"],
                ),
                first_message_test(
                    CLASSICAL,
                    Change::WrongResponderKey,
                    "initiator holds another responder key",
                    &["WrongResponderKey"],
                ),
            ],
        ),
        group(
            IKHFS,
            POST_QUANTUM,
            vec![
                first_message_test(POST_QUANTUM, Change::None, "post-quantum first message", &[]),
                first_message_test(
                    POST_QUANTUM,
                    Change::Seal(0),
                    "enc bit flipped before writing",
                    &["TamperedSeal"],
                ),
                first_message_test(
                    POST_QUANTUM,
                    Change::Seal(seal::ENC_LENGTH),
                    "ciphertext bit flipped before writing",
                    &["TamperedSeal"],
                ),
                first_message_test(
                    POST_QUANTUM,
                    Change::Transit(last(POST_QUANTUM)),
                    "payload tag bit flipped in transit",
                    &["Tampered"],
                ),
                first_message_test(
                    POST_QUANTUM,
                    Change::WrongResponderKey,
                    "initiator holds another responder key",
                    &["WrongResponderKey"],
                ),
            ],
        ),
    ];
    let side = |extra: Option<(&'static str, usize)>, ephemeral: bool| {
        let mut required = vec!["static_private"];
        let mut properties = vec![("static_private", s_hex_of(32))];
        if ephemeral {
            required.push("ephemeral_private");
            properties.push(("ephemeral_private", s_hex_of(32)));
        }
        if let Some((field, length)) = extra {
            properties.push((field, s_hex_of(length)));
        }
        required.push("prologue");
        properties.push(("prologue", s_hex()));
        s_object(&required, properties)
    };
    let initiator = side(Some(("kem_keygen_seed", 64)), true);
    let responder = side(Some(("xwing_secret_key", 32)), false);
    document(
        "first-message.json",
        "MeshLink/1 end-to-end first message (Noise IK and IKhfs message 1)",
        &[
            (
                "ReferenceKeys",
                "Keys, ephemerals, the KEM seed and the seal randomness come from fixed labels in tools/reference. The initiator is alice, the responder bob; responder_key is the static key the initiator holds for the responder (pre-message).",
            ),
            (
                "Replay",
                "The initiator writes message 1 from its inputs, responder_key and payload; its output equals message except for Tampered tests. For IK the payload is plaintext; for IKhfs it is the HPKE seal of plaintext with info and encapsulation_randomness (as hpke-seal.json), except for TamperedSeal tests. The responder reads message to payload, then for IKhfs opens payload to plaintext with xwing_secret_key. handshake-failed: the Noise read fails; open-failed: the read succeeds and the seal does not open.",
            ),
            ("Tampered", "One bit of message 1 flipped in transit."),
            (
                "TamperedSeal",
                "One bit of the HPKE seal flipped before the initiator writes it.",
            ),
            (
                "WrongResponderKey",
                "The initiator holds another node's static key for the responder.",
            ),
        ],
        groups,
        vec![
            ("now", s_integer(0)),
            ("initiator", initiator),
            ("responder", responder),
            ("responder_key", s_hex_of(32)),
            ("initiator_node_id", s_hex_of(32)),
            ("responder_node_id", s_hex_of(32)),
            ("plaintext", s_hex()),
            ("info?", s_hex()),
            ("encapsulation_randomness?", s_hex_of(64)),
            ("payload", s_hex()),
            ("message", s_hex()),
        ],
        vec![
            ("type", s_const(text("FirstMessage"))),
            ("protocol", s_enum(&[IK, IKHFS])),
            (
                "profile",
                object(vec![("enum", Value::List(vec![int(CLASSICAL), int(POST_QUANTUM)]))]),
            ),
        ],
    )
}

// ---------------------------------------------------------------------------
// End-to-end sessions (ticket 46a)
// ---------------------------------------------------------------------------

const REPLAY_WINDOW: u64 = 1_024;
const IK_RESPONDER: u8 = 0x02;
const TRANSPORT: u8 = 0x03;

/// `content` as a padded secured plaintext: `u32` length, content, zeros to a 32 B multiple.
fn padded(content: &[u8]) -> Vec<u8> {
    let mut out = u32::try_from(content.len()).expect("content").to_be_bytes().to_vec();
    out.extend_from_slice(content);
    out.resize(out.len().next_multiple_of(PADDING_BLOCK), 0);
    out
}

/// The padded `DELIVERY_RECEIPT` plaintext bob sends in message 2: its own message ID, the first
/// message's expiry and `MESSAGE_IDS` listing the first message.
fn receipt_plaintext(first_message_id: &[u8]) -> Vec<u8> {
    let extensions = tlv(MESSAGE_IDS, first_message_id);
    let mut content = seed("receipt message id")[..12].to_vec();
    content.extend_from_slice(&(NOW + 3_600).to_be_bytes());
    content.extend_from_slice(&u16::try_from(extensions.len()).expect("extensions").to_be_bytes());
    content.extend(extensions);
    padded(&content)
}

/// The padded `MESSAGE` plaintext a side sends at `nonce`: no extensions, a short payload.
fn session_plaintext(from_initiator: bool, nonce: u64) -> Vec<u8> {
    let who = if from_initiator { "initiator" } else { "responder" };
    let mut content = seed(&format!("{who} session message {nonce} id"))[..12].to_vec();
    content.extend_from_slice(&(NOW + 3_600).to_be_bytes());
    content.extend_from_slice(&seed(&format!("{who} session message {nonce} k"))[..16]);
    content.extend_from_slice(&0u16.to_be_bytes());
    content.extend_from_slice(format!("{who} frame {nonce}").as_bytes());
    padded(&content)
}

/// A kind `0x02` container: `u8 kind ‖ u32 length ‖ message 2`.
fn responder_container(message: &[u8]) -> Vec<u8> {
    let mut out = vec![IK_RESPONDER];
    out.extend_from_slice(&u32::try_from(message.len()).expect("message").to_be_bytes());
    out.extend_from_slice(message);
    out
}

/// One side's replay window: the nonces it has accepted.
#[derive(Default)]
struct Window {
    accepted: std::collections::BTreeSet<u64>,
}

impl Window {
    /// Why `nonce` is refused, if it is: not above `highest - REPLAY_WINDOW`, else accepted before.
    fn refuse(&self, nonce: u64) -> Option<&'static str> {
        let highest = self.accepted.last().copied();
        if highest.is_some_and(|highest| nonce + REPLAY_WINDOW <= highest) {
            Some("nonce-below-window")
        } else if self.accepted.contains(&nonce) {
            Some("replayed-nonce")
        } else {
            None
        }
    }
}

/// How one delivered transport frame departs from what its sender wrote.
#[derive(Clone, Copy, PartialEq)]
enum Delivery {
    AsWritten,
    /// The last tag bit flipped in transit.
    Tampered,
}

/// One delivered frame: its sender, nonce and delivery.
type Step = (bool, u64, Delivery);

fn e2e_session_group(profile: u8) -> Group {
    let (alice, bob) = (Identity::new("alice"), Identity::new("bob"));
    let post_quantum = profile == POST_QUANTUM;
    let protocol = if post_quantum { IKHFS } else { IK };
    let prologue = e2e_prologue(1, profile);
    let initiator_with = |ephemeral: &str| Side {
        static_private: alice.x25519_private,
        ephemeral_private: seed(ephemeral),
        kem: post_quantum.then_some(KemInput::Seed(seed64("alice first message kem"))),
        prologue: prologue.clone(),
        psk: None,
    };
    let initiator = initiator_with("alice first message ephemeral");
    let responder = Side {
        static_private: bob.x25519_private,
        ephemeral_private: seed("bob first message ephemeral"),
        kem: post_quantum.then_some(KemInput::Message(seed("bob first message kem"))),
        prologue: prologue.clone(),
        psk: None,
    };
    let plaintext = first_plaintext(&alice, profile);
    let first_message_id = plaintext[4..16].to_vec();
    let payload_1 = if post_quantum {
        let info = hpke_info(&alice.x25519_key(), &bob.node_id());
        seal::seal(&bob.xwing_key(), &info, &plaintext, &seed64("alice first message seal"))
    } else {
        plaintext
    };
    let receipt = receipt_plaintext(&first_message_id);
    let responder_key = bob.x25519_key();
    let open = || handshake::session(protocol, &initiator, &responder_key, &responder, &payload_1, &receipt);
    let reference = open();
    // bob's message 2 for a message 1 alice wrote with another ephemeral.
    let other = handshake::session(
        protocol,
        &initiator_with("alice other first message ephemeral"),
        &responder_key,
        &responder,
        &payload_1,
        &receipt,
    )
    .message_2;
    let handshake_test = |comment: &str, flag: &'static str, message_2: Vec<u8>| {
        let read = handshake::read_message_2(protocol, &initiator, &responder_key, &payload_1, &message_2);
        assert!(read.is_none(), "{comment}");
        test(
            comment,
            &[flag],
            vec![
                ("container_2", hex(&responder_container(&message_2))),
                ("frames", Value::List(Vec::new())),
            ],
            Some("handshake-failed"),
        )
    };
    let frames_test = |comment: &str, flags: &[&'static str], steps: &[Step]| {
        let session = open();
        let mut windows = [Window::default(), Window::default()];
        let mut first_error = None;
        let frames = steps
            .iter()
            .map(|&(from_initiator, nonce, delivery)| {
                let (writer, reader) = if from_initiator {
                    (&session.initiator, &session.responder)
                } else {
                    (&session.responder, &session.initiator)
                };
                let plaintext = session_plaintext(from_initiator, nonce);
                let mut ciphertext = vec![0; plaintext.len() + 16];
                let length = writer.write_message(nonce, &plaintext, &mut ciphertext).expect("seal");
                ciphertext.truncate(length);
                if delivery == Delivery::Tampered {
                    ciphertext = flip(&ciphertext, length - 1);
                }
                let mut container = vec![TRANSPORT];
                container.extend_from_slice(&nonce.to_be_bytes());
                container.extend_from_slice(&u32::try_from(length).expect("length").to_be_bytes());
                container.extend_from_slice(&ciphertext);
                let window = &mut windows[usize::from(!from_initiator)];
                let error = window.refuse(nonce).or_else(|| {
                    let mut opened = vec![0; length];
                    match reader.read_message(nonce, &ciphertext, &mut opened) {
                        Ok(n) => {
                            assert_eq!(&opened[..n], plaintext.as_slice(), "{comment}");
                            window.accepted.insert(nonce);
                            None
                        }
                        Err(_) => Some("open-failed"),
                    }
                });
                first_error = first_error.or(error);
                let mut pairs = vec![
                    ("from", text(if from_initiator { "initiator" } else { "responder" })),
                    ("nonce", int(i64::try_from(nonce).expect("nonce"))),
                    ("plaintext", hex(&plaintext)),
                    ("container", hex(&container)),
                ];
                if let Some(error) = error {
                    pairs.push(("error", text(error)));
                }
                object(pairs)
            })
            .collect();
        assert_eq!(first_error.is_some(), !flags.is_empty(), "{comment}");
        test(
            comment,
            flags,
            vec![
                ("container_2", hex(&responder_container(&session.message_2))),
                ("frames", Value::List(frames)),
            ],
            first_error,
        )
    };
    let i = |nonce| (true, nonce, Delivery::AsWritten);
    let r = |nonce| (false, nonce, Delivery::AsWritten);
    let last = reference.message_2.len() - 1;
    let tests = vec![
        frames_test(
            "frames each way, out of order, the last at the window edge",
            &[],
            &[i(0), r(0), i(2), i(1), r(1), i(1_026), i(3)],
        ),
        handshake_test(
            "message 2 with a flipped tag bit",
            "TamperedMessage2",
            flip(&reference.message_2, last),
        ),
        handshake_test("message 2 answering another first message", "OtherFirstMessage", other),
        frames_test("a replayed nonce", &["ReplayedNonce"], &[i(0), i(1), i(0), i(2)]),
        frames_test(
            "a nonce just below the window",
            &["BelowWindow"],
            &[i(0), i(1_026), i(2), i(3)],
        ),
        frames_test(
            "a frame with a flipped tag bit, then the frame as written",
            &["TamperedFrame"],
            &[i(0), (true, 1, Delivery::Tampered), i(1)],
        ),
    ];
    let initiator_json = side_json(&initiator, "kem_keygen_seed", None);
    let responder_json = side_json(&responder, "kem_encaps_seed", None);
    Group {
        attributes: vec![
            ("type", text("E2ESession")),
            ("protocol", text(protocol)),
            ("profile", int(profile)),
            ("initiator", initiator_json),
            ("responder", responder_json),
            ("responder_key", hex(&responder_key)),
            ("first_message_id", hex(&first_message_id)),
            ("payload_1", hex(&payload_1)),
            ("message_1", hex(&reference.message_1)),
            ("receipt_plaintext", hex(&receipt)),
            ("handshake_hash", hex(&reference.handshake_hash)),
        ],
        tests,
    }
}

fn e2e_sessions() -> Document {
    let frame = s_object(
        &["from", "nonce", "plaintext", "container"],
        vec![
            ("from", s_enum(&["initiator", "responder"])),
            ("nonce", s_integer(0)),
            ("plaintext", s_hex()),
            ("container", s_hex()),
            (
                "error",
                s_enum(&["replayed-nonce", "nonce-below-window", "open-failed"]),
            ),
        ],
    );
    document(
        "e2e-sessions.json",
        "MeshLink/1 end-to-end sessions (Noise IK and IKhfs message 2 and transport)",
        &[
            (
                "ReferenceKeys",
                "The inputs are those of the valid tests in first-message.json, plus the responder's ephemeral and, for IKhfs, its encapsulation message: fixed labels in tools/reference.",
            ),
            (
                "Replay",
                "The initiator writes message_1 from its inputs, responder_key and payload_1. The responder reads it, then writes message 2 with receipt_plaintext as its payload; container_2 is kind 02, a u32 length and message 2. The initiator reads message 2 with the state that wrote message_1: handshake-failed if the read fails. Otherwise both sides split, and the receipt's MESSAGE_IDS is first_message_id. Each frame is delivered in order: its sender seals plaintext at nonce with empty associated data, giving container (kind 03, u64 nonce, u32 length, ciphertext). Its receiver refuses a nonce with nonce + 1024 <= the highest nonce it accepted (nonce-below-window), then one it accepted before (replayed-nonce), then opens the ciphertext (open-failed). A refused or failed frame changes no state; a frame without error opens to plaintext. The test's error is its first frame error.",
            ),
            ("TamperedMessage2", "One tag bit of message 2 flipped in transit."),
            (
                "OtherFirstMessage",
                "Message 2 was written by the responder for a message 1 from another initiator ephemeral.",
            ),
            ("ReplayedNonce", "A frame delivered again at an accepted nonce."),
            (
                "BelowWindow",
                "A frame whose nonce plus 1024 equals the highest accepted nonce.",
            ),
            ("TamperedFrame", "One tag bit of a transport frame flipped in transit."),
        ],
        vec![e2e_session_group(CLASSICAL), e2e_session_group(POST_QUANTUM)],
        vec![("container_2", s_hex()), ("frames", s_list(frame, 0))],
        vec![
            ("type", s_const(text("E2ESession"))),
            ("protocol", s_enum(&[IK, IKHFS])),
            (
                "profile",
                object(vec![("enum", Value::List(vec![int(CLASSICAL), int(POST_QUANTUM)]))]),
            ),
            ("initiator", link_side(("kem_keygen_seed", 64))),
            ("responder", link_side(("kem_encaps_seed", 32))),
            ("responder_key", s_hex_of(32)),
            ("first_message_id", s_hex_of(12)),
            ("payload_1", s_hex()),
            ("message_1", s_hex()),
            ("receipt_plaintext", s_hex()),
            ("handshake_hash", s_hex_of(32)),
        ],
    )
}

// ---------------------------------------------------------------------------
// HPKE X-Wing seal (ticket 45)
// ---------------------------------------------------------------------------

fn hpke_seal() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let carol = Identity::new("carol");
    let recipient_key = bob.xwing_key();
    let recipient_secret_key = bob.xwing_secret_key();
    let sender_key = alice.x25519_key();
    let seal_test = |comment: &str,
                     flags: &[&'static str],
                     length: usize,
                     recipient_node_id: [u8; 32],
                     change: &dyn Fn(Vec<u8>) -> Vec<u8>| {
        let plaintext = crate::spec::seed64(&format!("plaintext {length}")).repeat(length / 64);
        let randomness = seed64(&format!("seal randomness {length}"));
        let sealed = seal::seal(
            &recipient_key,
            &hpke_info(&sender_key, &bob.node_id()),
            &plaintext,
            &randomness,
        );
        let payload = change(sealed);
        let info = hpke_info(&sender_key, &recipient_node_id);
        let opened = seal::open(&bob.xwing_ikm, &info, &payload);
        let error = if opened.is_some() {
            assert_eq!(opened.as_deref(), Some(plaintext.as_slice()), "{comment}");
            None
        } else {
            Some("open-failed")
        };
        let fields = vec![
            ("recipient_ikm", hex(&bob.xwing_ikm)),
            ("recipient_secret_key", hex(&recipient_secret_key)),
            ("recipient_public_key", hex(&recipient_key)),
            ("encapsulation_randomness", hex(&randomness)),
            ("sender_static_key", hex(&sender_key)),
            ("recipient_node_id", hex(&recipient_node_id)),
            ("info", hex(&info)),
            ("plaintext", hex(&plaintext)),
            ("payload", hex(&payload)),
        ];
        test(comment, flags, fields, error)
    };
    let same = |p: Vec<u8>| p;
    let tests = vec![
        seal_test("64 B padded plaintext", &[], 64, bob.node_id(), &same),
        seal_test("256 B padded plaintext", &[], 256, bob.node_id(), &same),
        seal_test("flipped ciphertext bit", &["Tampered"], 64, bob.node_id(), &|p| {
            flip(&p, seal::ENC_LENGTH)
        }),
        seal_test("flipped tag bit", &["Tampered"], 64, bob.node_id(), &|p| {
            flip(&p, p.len() - 1)
        }),
        seal_test("flipped enc bit", &["Tampered"], 64, bob.node_id(), &|p| flip(&p, 0)),
        seal_test(
            "info names another recipient NodeId",
            &["WrongInfo"],
            64,
            carol.node_id(),
            &same,
        ),
        seal_test("last byte missing", &["Tampered"], 64, bob.node_id(), &|p| {
            p[..p.len() - 1].to_vec()
        }),
    ];
    document(
        "hpke-seal.json",
        "MeshLink/1 first-message seal (HPKE base, X-Wing, HKDF-SHA256, ChaCha20Poly1305)",
        &[
            (
                "ReferenceKeys",
                "Keys come from fixed labels in tools/reference. recipient_secret_key (the 32 B X-Wing seed) and recipient_public_key are HPKE DeriveKeyPair(recipient_ikm); encapsulation_randomness is the 64 B X-Wing Encaps input (draft-ietf-hpke-pq-05).",
            ),
            (
                "Replay",
                "A sender with these inputs and aad empty produces payload = enc (1,120 B) ‖ ct for valid tests. A recipient opens payload with recipient_secret_key and info; invalid tests must not open.",
            ),
            ("Tampered", "The payload changed after sealing."),
            (
                "WrongInfo",
                "The payload was sealed to the right recipient, but info names another NodeId.",
            ),
        ],
        vec![Group {
            attributes: vec![
                ("type", text("HpkeSeal")),
                ("mode", int(0)),
                ("kem_id", int(0x647a)),
                ("kdf_id", int(1)),
                ("aead_id", int(3)),
            ],
            tests,
        }],
        vec![
            ("recipient_ikm", s_hex_of(32)),
            ("recipient_secret_key", s_hex_of(32)),
            ("recipient_public_key", s_hex_of(1_216)),
            ("encapsulation_randomness", s_hex_of(64)),
            ("sender_static_key", s_hex_of(32)),
            ("recipient_node_id", s_hex_of(32)),
            ("info", s_hex()),
            ("plaintext", s_hex()),
            ("payload", s_hex()),
        ],
        vec![
            ("type", s_const(text("HpkeSeal"))),
            ("mode", s_const(int(0))),
            ("kem_id", s_const(int(0x647a))),
            ("kdf_id", s_const(int(1))),
            ("aead_id", s_const(int(3))),
        ],
    )
}

// ---------------------------------------------------------------------------
// Certificate lookup reply seal (ticket 44)
// ---------------------------------------------------------------------------

/// A point of order 8 on Curve25519 (RFC 7748 section 6.1 requires rejecting its all-zero result).
const ORDER_8: &str = "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800";

struct Lookup {
    requester: Option<[u8; 32]>,
    requester_key: [u8; 32],
    responder: Option<[u8; 32]>,
    responder_key: [u8; 32],
}

impl Lookup {
    fn new() -> Self {
        let (requester, responder) = (seed("lookup requester ephemeral"), seed("lookup responder ephemeral"));
        Lookup {
            requester: Some(requester),
            requester_key: crate::spec::x25519_public(&requester),
            responder: Some(responder),
            responder_key: crate::spec::x25519_public(&responder),
        }
    }
}

fn lookup_test(
    comment: &str,
    flags: &[&'static str],
    profile: u8,
    sides: &Lookup,
    seal_salt: &[u8],
    change: &dyn Fn(Vec<u8>) -> Vec<u8>,
) -> Test {
    let alice = Identity::new("alice");
    let certificate = alice.certificate(profile, NOT_BEFORE, NOT_AFTER);
    let salt = &seed("lookup salt")[..8];
    let tag = lookup::tag(salt, &alice.node_id());
    let mut fields = vec![
        ("target_node_id", hex(&alice.node_id())),
        ("now", int(i64::from(NOW))),
        ("certificate", hex(&certificate)),
        ("salt", hex(salt)),
        ("lookup_tag", hex(&tag)),
        ("requester_ephemeral", hex(&sides.requester_key)),
        ("responder_ephemeral", hex(&sides.responder_key)),
    ];
    if let Some(private) = sides.requester {
        fields.push(("requester_private", hex(&private)));
    }
    if let Some(private) = sides.responder {
        fields.push(("responder_private", hex(&private)));
    }
    let Some(responder) = sides.responder else {
        // A forged reply: the attacker's low-order key makes the shared secret zero, so it can seal.
        let requester = sides.requester.expect("one honest side");
        assert_eq!(lookup::shared(&requester, &sides.responder_key), None, "{comment}");
        let key = lookup::key(&[0; 32], salt, &tag, &sides.requester_key, &sides.responder_key);
        fields.push(("shared_secret", hex(&[0; 32])));
        fields.push(("key", hex(&key)));
        fields.push(("sealed", hex(&lookup::seal(&key, &certificate))));
        return test(comment, flags, fields, Some("low-order-key"));
    };
    let Some(shared) = lookup::shared(&responder, &sides.requester_key) else {
        return test(comment, flags, fields, Some("low-order-key"));
    };
    let requester = sides.requester.expect("an honest requester");
    assert_eq!(
        lookup::shared(&requester, &sides.responder_key),
        Some(shared),
        "{comment}"
    );
    let key = lookup::key(&shared, salt, &tag, &sides.requester_key, &sides.responder_key);
    let sealing = lookup::key(&shared, seal_salt, &tag, &sides.requester_key, &sides.responder_key);
    let sealed = change(lookup::seal(&sealing, &certificate));
    let error = match lookup::open(&key, &sealed) {
        Some(opened) => {
            assert_eq!(opened, certificate, "{comment}");
            None
        }
        None => Some("open-failed"),
    };
    fields.push(("shared_secret", hex(&shared)));
    fields.push(("key", hex(&key)));
    fields.push(("sealed", hex(&sealed)));
    test(comment, flags, fields, error)
}

fn lookup_reply() -> Document {
    let honest = Lookup::new();
    let salt = &seed("lookup salt")[..8];
    let other_salt = &seed("lookup salt 2")[..8];
    let same = |p: Vec<u8>| p;
    let order_8: [u8; 32] = (0..32)
        .map(|i| u8::from_str_radix(&ORDER_8[2 * i..2 * i + 2], 16).expect("hex"))
        .collect::<Vec<_>>()
        .try_into()
        .expect("32 B point");
    let low_requester = Lookup {
        requester: None,
        requester_key: [0; 32],
        ..Lookup::new()
    };
    let low_responder = Lookup {
        responder: None,
        responder_key: order_8,
        ..Lookup::new()
    };
    let tests = vec![
        lookup_test("classical certificate", &[], CLASSICAL, &honest, salt, &same),
        lookup_test("post-quantum certificate", &[], POST_QUANTUM, &honest, salt, &same),
        lookup_test(
            "flipped ciphertext bit",
            &["Tampered"],
            CLASSICAL,
            &honest,
            salt,
            &|p| flip(&p, 0),
        ),
        lookup_test("flipped tag bit", &["Tampered"], CLASSICAL, &honest, salt, &|p| {
            flip(&p, p.len() - 1)
        }),
        lookup_test(
            "sealed for another salt",
            &["WrongBinding"],
            CLASSICAL,
            &honest,
            other_salt,
            &same,
        ),
        lookup_test(
            "requester ephemeral is zero",
            &["LowOrder"],
            CLASSICAL,
            &low_requester,
            salt,
            &same,
        ),
        lookup_test(
            "responder ephemeral of order 8, sealed under the zero secret",
            &["LowOrder"],
            CLASSICAL,
            &low_responder,
            salt,
            &same,
        ),
    ];
    document(
        "lookup-reply.json",
        "MeshLink/1 certificate lookup reply seal (X25519, HKDF-SHA256, ChaCha20Poly1305)",
        &[
            (
                "ReferenceKeys",
                "Keys come from fixed labels in tools/reference. Each side's ephemeral is the X25519 public key of its _private field; a field is absent when that side's key is the low-order key shown.",
            ),
            (
                "Replay",
                "The responder computes shared_secret from responder_private and requester_ephemeral, then key = KDF(shared_secret, salt ‖ lookup_tag, \"lookup-reply\", requester_ephemeral ‖ responder_ephemeral, 32), and seals certificate with ChaCha20-Poly1305, nonce 12 zero bytes, empty associated data: valid tests MUST produce sealed. The requester computes the same key from requester_private and responder_ephemeral and MUST open sealed to certificate, which validates for target_node_id at now; invalid tests MUST NOT yield a certificate.",
            ),
            ("Tampered", "The sealed bytes changed after sealing."),
            (
                "WrongBinding",
                "The certificate was sealed under the key of another salt.",
            ),
            (
                "LowOrder",
                "One ephemeral has low order, so the shared secret is all zero. The other side MUST refuse before the AEAD: the responder seals nothing, and the requester never opens the forged sealed, which an attacker can produce under the zero secret.",
            ),
        ],
        vec![Group {
            attributes: vec![("type", text("LookupReplySeal"))],
            tests,
        }],
        vec![
            ("target_node_id", s_hex_of(32)),
            ("now", s_integer(0)),
            ("certificate", s_hex()),
            ("salt", s_hex_of(8)),
            ("lookup_tag", s_hex_of(8)),
            ("requester_ephemeral", s_hex_of(32)),
            ("responder_ephemeral", s_hex_of(32)),
            ("requester_private?", s_hex_of(32)),
            ("responder_private?", s_hex_of(32)),
            ("shared_secret?", s_hex_of(32)),
            ("key?", s_hex_of(32)),
            ("sealed?", s_hex()),
        ],
        vec![("type", s_const(text("LookupReplySeal")))],
    )
}

// ---------------------------------------------------------------------------
// Link records, REKEY (ticket 30) and BEARER_SWITCH (ticket 31)
// ---------------------------------------------------------------------------

const REKEY: u8 = 0x05;
const BEARER_SWITCH: u8 = 0x0F;

/// Changes the last record's bytes: given its bytes, its bytes from a state that never rekeyed, and
/// its bytes from a fresh state at nonce 0.
type Tamper = dyn Fn(Vec<u8>, Vec<u8>, Vec<u8>) -> Vec<u8>;

/// One record as sent: who sent it, the frame it carries and its bytes on the stream.
struct Record {
    from_initiator: bool,
    plaintext: Vec<u8>,
    bytes: Vec<u8>,
}

/// `plaintext` sealed by `writer` as one link record: a `u16` length, then the ciphertext.
/// Sealing a `REKEY` rekeys the writer's sending direction after it.
fn seal_record(writer: &mut snow::TransportState, plaintext: &[u8]) -> Vec<u8> {
    let mut ciphertext = vec![0; plaintext.len() + 16];
    let length = writer.write_message(plaintext, &mut ciphertext).expect("seal");
    if plaintext == [REKEY] {
        writer.rekey_outgoing();
    }
    let mut record = u16::try_from(length).expect("length").to_be_bytes().to_vec();
    record.extend_from_slice(&ciphertext[..length]);
    record
}

/// Opens each record at its reader, rekeying after a `REKEY`: the 1-based index of the first
/// record that fails to decrypt, if any.
fn open_records(sides: &mut [snow::TransportState; 2], records: &[Record]) -> Option<usize> {
    for (index, record) in records.iter().enumerate() {
        let reader = &mut sides[usize::from(record.from_initiator)];
        let mut plaintext = vec![0; record.bytes.len()];
        match reader.read_message(&record.bytes[2..], &mut plaintext) {
            Ok(length) => {
                assert_eq!(
                    &plaintext[..length],
                    record.plaintext.as_slice(),
                    "record {}",
                    index + 1
                );
                if record.plaintext == [REKEY] {
                    reader.rekey_incoming();
                }
            }
            Err(_) => return Some(index + 1),
        }
    }
    None
}

/// One side's inputs in a link transport group: the KEM input only for `XXhfs`, no mesh key.
fn link_side((field, length): (&'static str, usize)) -> Value {
    s_object(
        &["static_private", "ephemeral_private", "prologue"],
        vec![
            ("static_private", s_hex_of(32)),
            ("ephemeral_private", s_hex_of(32)),
            (field, s_hex_of(length)),
            ("prologue", s_hex()),
        ],
    )
}

fn link_transport() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let groups = [(XX, CLASSICAL), (XXHFS, POST_QUANTUM)]
        .into_iter()
        .map(|(protocol, profile)| {
            let prologue = link_prologue(&[1], 1, profile, &mesh_id_digest(MESH_ID, profile));
            let setup = Setup {
                protocol,
                profile,
                alice: &alice,
                bob: &bob,
                prologues: (&prologue, &prologue),
                mesh_keys: None,
            };
            let (payloads, initiator, responder) = sides(setup);
            let transports = || handshake::transports(protocol, &initiator, &responder, &payloads);
            let frame = |from_initiator: bool, n: usize| {
                let who = if from_initiator { "initiator" } else { "responder" };
                format!("{who} frame {n}").into_bytes()
            };
            // Writes `script` (sender, frame) in order from fresh transports; `tamper` changes the
            // last record's bytes before delivery, given its bytes from the writer's state, from a
            // state that never rekeyed and from a fresh state at nonce 0.
            let records = |script: &[(bool, Vec<u8>)], tamper: &Tamper| {
                let [mut i, mut r] = transports();
                let [mut stale, _] = transports();
                let [mut fresh, _] = transports();
                let mut out: Vec<Record> = Vec::new();
                for (index, (from_initiator, plaintext)) in script.iter().enumerate() {
                    let writer = if *from_initiator { &mut i } else { &mut r };
                    let mut bytes = seal_record(writer, plaintext);
                    if *from_initiator {
                        let mut ciphertext = vec![0; plaintext.len() + 16];
                        let length = stale.write_message(plaintext, &mut ciphertext).expect("stale");
                        let mut old = u16::try_from(length).expect("length").to_be_bytes().to_vec();
                        old.extend_from_slice(&ciphertext[..length]);
                        if index + 1 == script.len() {
                            bytes = tamper(bytes, old, seal_record(&mut fresh, plaintext));
                        }
                    }
                    out.push(Record {
                        from_initiator: *from_initiator,
                        plaintext: plaintext.clone(),
                        bytes,
                    });
                }
                out
            };
            let rekey = vec![REKEY];
            let mut valid = Vec::new();
            for from_initiator in [true, false] {
                valid.extend((1..=3).map(|n| (from_initiator, frame(from_initiator, n))));
            }
            valid.extend([(true, rekey.clone()), (false, rekey.clone())]);
            for from_initiator in [true, false] {
                valid.extend((4..=5).map(|n| (from_initiator, frame(from_initiator, n))));
            }
            let mut after = (1..=3).map(|n| (true, frame(true, n))).collect::<Vec<_>>();
            after.extend([(true, rekey.clone()), (true, frame(true, 4))]);
            // The central (initiator) switches first; each side's records after its own
            // BEARER_SWITCH travel on CoC at the next nonce.
            let switch = vec![BEARER_SWITCH];
            let mut switched = Vec::new();
            for from_initiator in [true, false] {
                switched.extend((1..=3).map(|n| (from_initiator, frame(from_initiator, n))));
            }
            switched.extend([(true, switch.clone()), (false, switch.clone())]);
            for from_initiator in [true, false] {
                switched.extend((4..=5).map(|n| (from_initiator, frame(from_initiator, n))));
            }
            let mut moved = (1..=3).map(|n| (true, frame(true, n))).collect::<Vec<_>>();
            moved.extend([(true, switch.clone()), (true, frame(true, 4))]);
            let case = |comment: &str, flags: &[&'static str], script: &[(bool, Vec<u8>)], tamper: &Tamper| {
                let records = records(script, tamper);
                let rejected = open_records(&mut transports(), &records);
                assert_eq!(rejected.is_some(), !flags.is_empty(), "{comment}");
                let direction = |from_initiator: bool| {
                    let mine = records
                        .iter()
                        .filter(move |record| record.from_initiator == from_initiator);
                    let frames = mine.clone().map(|record| hex(&record.plaintext)).collect();
                    (
                        Value::List(frames),
                        hex(&mine.flat_map(|record| record.bytes.clone()).collect::<Vec<_>>()),
                    )
                };
                let (initiator_frames, initiator_stream) = direction(true);
                let (responder_frames, responder_stream) = direction(false);
                let mut fields = vec![
                    ("initiator_frames", initiator_frames),
                    ("initiator_stream", initiator_stream),
                    ("responder_frames", responder_frames),
                    ("responder_stream", responder_stream),
                ];
                if let Some(index) = rejected {
                    let index = records[..index].iter().filter(|record| record.from_initiator).count();
                    fields.push(("rejected_record", int(i64::try_from(index).expect("index"))));
                }
                test(comment, flags, fields, rejected.map(|_| "decrypt-failed"))
            };
            let handshake_hash = handshake::run(protocol, &initiator, &responder, &payloads, None)
                .outcome
                .map(|completed| completed.handshake_hash)
                .unwrap_or_else(|_| panic!("{protocol}: handshake"));
            let tests = vec![
                case(
                    "three records each way, REKEY each way, then two each way",
                    &[],
                    &valid,
                    &|bytes, _, _| bytes,
                ),
                case(
                    "a record sealed with the key before REKEY",
                    &["OldKey"],
                    &after,
                    &|_, old, _| old,
                ),
                case(
                    "a record after REKEY with a flipped tag bit",
                    &["Tampered"],
                    &after,
                    &|bytes, _, _| flip(&bytes, bytes.len() - 1),
                ),
                case(
                    "three records each way on GATT, BEARER_SWITCH each way, then two each way on CoC",
                    &[],
                    &switched,
                    &|bytes, _, _| bytes,
                ),
                case(
                    "the first CoC record after BEARER_SWITCH sealed at nonce 0",
                    &["NonceReset"],
                    &moved,
                    &|_, _, reset| reset,
                ),
            ];
            Group {
                attributes: vec![
                    ("type", text("LinkTransport")),
                    ("protocol", text(protocol)),
                    ("initiator", side_json(&initiator, "kem_keygen_seed", None)),
                    ("responder", side_json(&responder, "kem_encaps_seed", None)),
                    ("payloads", Value::List(payloads.iter().map(|p| hex(p)).collect())),
                    ("handshake_hash", hex(&handshake_hash)),
                ],
                tests,
            }
        })
        .collect();
    document(
        "link-transport.json",
        "MeshLink/1 link records, REKEY and BEARER_SWITCH (Noise XX, XXhfs transport)",
        &[
            (
                "ReferenceKeys",
                "The handshake inputs are those of the open valid tests in handshake-xx.json and handshake-hfs.json: fixed labels in tools/reference.",
            ),
            (
                "Replay",
                "Both sides run the group's handshake. Each then seals its frames in order as link records, a u16 length and then the ciphertext of the frame with empty associated data; its stream is those records back to back. After sealing the REKEY frame (05) a writer calls Rekey() on its sending cipher state; after opening one a reader calls Rekey() on its receiving cipher state; the nonce carries on. A BEARER_SWITCH frame (0f) changes no cipher state: the records after it continue the stream on the L2CAP CoC channel at the next nonce. A writer's own records equal its stream except at rejected_record. The responder must fail to open record rejected_record of initiator_stream and open every earlier one; every other record opens to its frame.",
            ),
            (
                "OldKey",
                "The last record was sealed at the next nonce under the key before REKEY.",
            ),
            ("Tampered", "One tag bit of the last record flipped in transit."),
            (
                "NonceReset",
                "The first record after BEARER_SWITCH was sealed at nonce 0 with the same key, as if the cipher state restarted on the new bearer.",
            ),
        ],
        groups,
        vec![
            ("initiator_frames", s_list(s_hex(), 1)),
            ("initiator_stream", s_hex()),
            ("responder_frames", s_list(s_hex(), 0)),
            ("responder_stream", s_hex()),
            ("rejected_record?", s_integer(1)),
        ],
        vec![
            ("type", s_const(text("LinkTransport"))),
            ("protocol", s_enum(&[XX, XXHFS])),
            ("initiator", link_side(("kem_keygen_seed", 64))),
            ("responder", link_side(("kem_encaps_seed", 32))),
            ("payloads", s_list(s_hex(), 3)),
            ("handshake_hash", s_hex_of(32)),
        ],
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn committed_files_match_the_generator() {
        for document in all() {
            for (name, value) in [
                (document.name.to_owned(), &document.vectors),
                (document.schema_name(), &document.schema),
            ] {
                let path = format!("{DIRECTORY}/{name}");
                let committed = std::fs::read_to_string(&path).unwrap_or_default();
                assert!(
                    committed == value.to_pretty(),
                    "{name} is stale: run cargo run --manifest-path tools/reference/Cargo.toml"
                );
            }
        }
    }
}
