//! The tier 2 vector files and their schemas, in the vectors README format.

use crate::certificate::{self, CLASSICAL, CLOCK_SKEW, Identity, POST_QUANTUM, VALIDITY_MAX};
use crate::handshake::{self, KemInput, Rejected, Side};
use crate::json::{Value, hex, int, object, text, texts};
use crate::seal;
use crate::spec::{flip, hpke_info, link_prologue, mesh_id_digest, mesh_psk, seed, seed64, tlv};

/// Where the files live, relative to this crate.
pub const DIRECTORY: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../docs/spec/vectors");

const NOT_BEFORE: u32 = 1_798_761_600;
const NOT_AFTER: u32 = NOT_BEFORE + VALIDITY_MAX;
const NOW: u32 = NOT_BEFORE + 3_600;
const MESH_ID: &str = "reference-mesh";
const XXHFS: &str = "Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256";
const XXHFSPSK3: &str = "Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256";
const RESOLVING_KEY: u8 = 0x02;
const CERTIFICATE: u8 = 0x81;

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
    vec![certificate_signatures(), handshake_hfs(), hpke_seal()]
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
    let side = |kem: (&str, usize)| {
        s_object(
            &["static_private", "ephemeral_private", kem.0, "prologue"],
            vec![
                ("static_private", s_hex_of(32)),
                ("ephemeral_private", s_hex_of(32)),
                (kem.0, s_hex_of(kem.1)),
                ("prologue", s_hex()),
                ("mesh_key", s_hex_of(32)),
                ("psk", s_hex_of(32)),
            ],
        )
    };
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
        "handshake-hfs.json",
        "MeshLink/1 post-quantum link handshake (Noise XXhfs, XXhfs+psk3)",
        &[
            (
                "ReferenceKeys",
                "Keys, ephemerals and KEM inputs come from fixed labels in tools/reference. Private keys are the raw 32 B X25519 inputs. kem_keygen_seed is the FIPS 203 ML-KEM.KeyGen_internal seed d ‖ z; kem_encaps_seed is the ML-KEM.Encaps_internal message m.",
            ),
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
            ("initiator", side(("kem_keygen_seed", 64))),
            ("responder", side(("kem_encaps_seed", 32))),
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
            ("protocol", s_enum(&[XXHFS, XXHFSPSK3])),
            ("gated", s_type("boolean")),
        ],
    )
}

/// The inputs shared by the tests of one handshake: the initiator is `alice`, the responder `bob`.
#[derive(Clone, Copy)]
struct Setup<'a> {
    protocol: &'static str,
    alice: &'a Identity,
    bob: &'a Identity,
    prologues: (&'a [u8], &'a [u8]),
    mesh_keys: Option<(&'a [u8; 32], &'a [u8; 32])>,
}

fn handshake_test(setup: Setup, tamper: Option<(usize, usize)>, comment: &str, flags: &[&'static str]) -> Test {
    let Setup {
        protocol,
        alice,
        bob,
        prologues,
        mesh_keys,
    } = setup;
    let gated = mesh_keys.is_some();
    let payload = |who: &Identity, label: &str| {
        let mut out = Vec::new();
        if gated {
            let mut value = seed(&format!("{label} resolving key")).to_vec();
            value.extend_from_slice(&NOT_AFTER.to_be_bytes());
            out.extend(tlv(RESOLVING_KEY, &value));
        }
        out.extend(tlv(CERTIFICATE, &who.certificate(POST_QUANTUM, NOT_BEFORE, NOT_AFTER)));
        out
    };
    let payloads = [Vec::new(), payload(bob, "bob"), payload(alice, "alice")];
    let make = |who: &Identity, label: &str, kem: KemInput, prologue: &[u8], mesh_key: Option<&[u8; 32]>| Side {
        static_private: who.x25519_private,
        ephemeral_private: seed(&format!("{label} handshake ephemeral")),
        kem,
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
    let transcript = handshake::run(protocol, &initiator, &responder, &payloads, tamper);
    let side_json = |side: &Side, kem_name: &'static str, mesh_key: Option<&[u8; 32]>| {
        let mut pairs = vec![
            ("static_private", hex(&side.static_private)),
            ("ephemeral_private", hex(&side.ephemeral_private)),
            (kem_name, hex(&side.kem.bytes())),
            ("prologue", hex(&side.prologue)),
        ];
        if let (Some(key), Some(psk)) = (mesh_key, side.psk) {
            pairs.push(("mesh_key", hex(key)));
            pairs.push(("psk", hex(&psk)));
        }
        object(pairs)
    };
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
// HPKE X-Wing seal (ticket 45)
// ---------------------------------------------------------------------------

fn hpke_seal() -> Document {
    let alice = Identity::new("alice");
    let bob = Identity::new("bob");
    let carol = Identity::new("carol");
    let recipient_key = bob.xwing_key();
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
                "Keys come from fixed labels in tools/reference. recipient_public_key is the X-Wing key of HPKE DeriveKeyPair(recipient_ikm); encapsulation_randomness is the 64 B X-Wing Encaps input (draft-ietf-hpke-pq-05).",
            ),
            (
                "Replay",
                "A sender with these inputs and aad empty produces payload = enc (1,120 B) ‖ ct for valid tests. A recipient opens payload with recipient_ikm and info; invalid tests must not open.",
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
