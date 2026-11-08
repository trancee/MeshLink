#!/usr/bin/env python3
"""Generate MeshLink protocol 1 tier-1 test vectors into docs/spec/vectors.

Python 3 standard library only. Deterministic: every byte comes from fixed
labels, so a rerun produces identical files. Signatures, public keys and
ciphertexts in layout vectors are placeholders: those vectors test framing only.
"""

import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mlspec as m  # noqa: E402

PLACEHOLDER = "Placeholder"


def det(label, n):
    """Deterministic pseudo-random bytes for a label (SHA-256 counter mode)."""
    out, i = b"", 0
    while len(out) < n:
        out += hashlib.sha256(("MeshLink spec vectors|%s|%d" % (label, i)).encode()).digest()
        i += 1
    return out[:n]


def dh(label, n):
    return det(label, n).hex()


class File:
    def __init__(self, name, algorithm, kind, notes):
        self.name, self.algorithm, self.kind, self.notes = name, algorithm, kind, notes
        self.groups = []
        self.count = 0

    def group(self, **attrs):
        g = dict(attrs)
        g["tests"] = []
        self.groups.append(g)
        return g

    def add(self, g, comment, result, flags=(), **fields):
        self.count += 1
        t = {"tcId": self.count, "comment": comment, "flags": list(flags), "result": result}
        t.update(fields)
        g["tests"].append(t)

    def write(self):
        schema = self.name.replace(".json", ".schema.json")
        doc = {"$schema": "./" + schema, "algorithm": self.algorithm,
               "schema": schema, "generatorVersion": "1",
               "numberOfTests": self.count, "notes": self.notes,
               "testGroups": self.groups}
        _dump(os.path.join(m.VECTORS, self.name), doc)
        _dump(os.path.join(m.VECTORS, schema), vector_schema(self.kind, self.name))


def _dump(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, sort_keys=False)
        f.write("\n")


HEX = {"type": "string", "pattern": "^([0-9a-f]{2})*$"}


def vector_schema(kind, name):
    base = {
        "tcId": {"type": "integer", "minimum": 1},
        "comment": {"type": "string"},
        "flags": {"type": "array", "items": {"type": "string"}},
        "result": {"enum": ["valid", "invalid", "acceptable"]},
    }
    if kind == "derivation":
        test = {"type": "object", "required": ["tcId", "comment", "flags", "result", "inputs", "output"],
                "properties": dict(base, inputs={"type": "object"}, output=HEX),
                "additionalProperties": False}
        group = {"type": "object", "required": ["derivation", "tests"],
                 "properties": {"derivation": {"type": "string"}, "domain": {"type": "string"},
                                "tests": {"type": "array", "items": test}},
                 "additionalProperties": False}
    else:
        test = {"type": "object", "required": ["tcId", "comment", "flags", "result", "encoded"],
                "properties": dict(base, encoded=HEX, decoded={"type": "object"}, error={"type": "string"}),
                "additionalProperties": False}
        group = {"type": "object", "required": ["layout", "tests"],
                 "properties": {"layout": {"type": "string"}, "secured": {"type": "boolean"},
                                "tests": {"type": "array", "items": test}},
                 "additionalProperties": False}
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": name.replace(".json", ".schema.json"),
        "title": "MeshLink protocol 1 test vectors: " + name,
        "type": "object",
        "required": ["algorithm", "schema", "generatorVersion", "numberOfTests", "notes", "testGroups"],
        "properties": {
            "$schema": {"type": "string"},
            "algorithm": {"type": "string"},
            "schema": {"type": "string"},
            "generatorVersion": {"type": "string"},
            "numberOfTests": {"type": "integer", "minimum": 1},
            "notes": {"type": "object"},
            "testGroups": {"type": "array", "minItems": 1, "items": group},
        },
        "additionalProperties": False,
    }


# ---------------------------------------------------------------------------
# Layout helpers: expected outcomes are cross-checked against the reference codec
# ---------------------------------------------------------------------------


def _decode(layout, data, group):
    if layout == "frame":
        return m.decode_frame(data)
    if layout == "record":
        return m.decode_record(data), "valid"
    if layout == "advertisement":
        return m.decode_advertisement(data)
    if layout == "capability":
        return m.decode_capability(data), "valid"
    if layout == "certificate":
        return m.decode_certificate(data), "valid"
    if layout == "handshake-payload":
        return m.decode_handshake_payload(data), "valid"
    if layout.startswith("inner-"):
        return m.decode_inner(data, layout[6:], group["secured"]), "valid"
    raise AssertionError(layout)


def outcome(layout, data, group):
    try:
        fields, result = _decode(layout, data, group)
        return result, fields, None
    except m.DecodeError as e:
        return "invalid", None, e.code


def add_ok(f, g, comment, data, flags=(), expect="valid"):
    result, fields, err = outcome(g["layout"], data, g)
    assert result == expect, (comment, result, err)
    f.add(g, comment, result, flags, encoded=data.hex(), decoded=fields)
    return data


def add_bad(f, g, comment, data, error, flags=()):
    result, _, err = outcome(g["layout"], data, g)
    assert result == "invalid" and err == error, (comment, result, err, error)
    f.add(g, comment, "invalid", flags, encoded=data.hex(), error=error)


def auto_mutations(f, g, name, data):
    """Truncated and trailing-byte variants, added only where they are invalid."""
    r, _, e = outcome(g["layout"], data[:-1], g)
    if r == "invalid" and e == "truncated":
        add_bad(f, g, name + ": last byte missing", data[:-1], "truncated", ["Truncated"])
    r, _, e = outcome(g["layout"], data + b"\x00", g)
    if r == "invalid" and e == "trailing-bytes":
        add_bad(f, g, name + ": one trailing byte", data + b"\x00", "trailing-bytes", ["TrailingBytes"])


# ---------------------------------------------------------------------------
# Derivations
# ---------------------------------------------------------------------------


def gen_derivations():
    f = File("derivations.json", "MeshLink/1 derivations", "derivation",
             {"Derivation": "Hash-only derivations; recomputable with BLAKE2b and HKDF-SHA-256."})
    ep = [m.epoch_of(1798761600), m.epoch_of(1798761600) + 1, 0]

    g = f.group(derivation="epoch")
    for t in (0, 299, 300, 1798761600, 4294967295):
        f.add(g, "unix %d" % t, "valid", inputs={"unix_seconds": t}, output=m.u32(m.epoch_of(t)).hex())

    g = f.group(derivation="node-id", domain=m.DOMAINS["node-id"])
    for i in range(3):
        pk = det("ed25519-pk-%d" % i, 32)
        f.add(g, "Ed25519 key %d (placeholder bytes)" % i, "valid", inputs={"ed25519_key": pk.hex()},
              output=m.node_id(pk).hex())

    g = f.group(derivation="mesh-id-digest", domain=m.DOMAINS["mesh-id"])
    for mid, prof in (("Festival", 1), ("Festival", 2), ("a", 0), ("Ünïcödé mesh", 1), ("x" * 64, 2)):
        f.add(g, "mesh id %r profile %d" % (mid if len(mid) < 20 else mid[:8] + "...", prof), "valid",
              inputs={"mesh_id_utf8": mid.encode().hex(), "profile": prof},
              output=m.mesh_id_digest(mid, prof).hex())

    g = f.group(derivation="mesh-tag-key", domain=m.DOMAINS["mesh-tag-key"])
    for i in range(2):
        dg = m.mesh_id_digest("Field team %d" % i, 1)
        mk = det("mesh-key-%d" % i, 32)
        f.add(g, "gated mesh %d" % i, "valid", inputs={"mesh_key": mk.hex(), "mesh_id_digest": dg.hex()},
              output=m.mesh_tag_key(dg, mk).hex())

    g = f.group(derivation="mesh-tag", domain=m.DOMAINS["mesh-tag"])
    dg = m.mesh_id_digest("Festival", 1)
    for e in ep:
        f.add(g, "open mesh, key = mesh id digest, epoch %d" % e, "valid",
              inputs={"tag_key": dg.hex(), "epoch": e}, output=m.mesh_tag(dg, e).hex())
    tk = m.mesh_tag_key(m.mesh_id_digest("Field team 0", 1), det("mesh-key-0", 32))
    for e in ep[:2]:
        f.add(g, "gated mesh, derived tag key, epoch %d" % e, "valid",
              inputs={"tag_key": tk.hex(), "epoch": e}, output=m.mesh_tag(tk, e).hex())

    g = f.group(derivation="mesh-psk", domain=m.DOMAINS["mesh-psk"])
    for i in range(2):
        dg = m.mesh_id_digest("Field team %d" % i, 1 + i)
        mk = det("mesh-key-%d" % i, 32)
        f.add(g, "gated mesh %d" % i, "valid", inputs={"mesh_key": mk.hex(), "mesh_id_digest": dg.hex()},
              output=m.mesh_psk(mk, dg).hex())

    g = f.group(derivation="resolvable-tag", domain=m.DOMAINS["resolvable-tag"])
    rk = det("resolving-key", 32)
    for e in ep:
        f.add(g, "epoch %d" % e, "valid", inputs={"resolving_key": rk.hex(), "epoch": e},
              output=m.resolvable_tag(rk, e).hex())

    g = f.group(derivation="public-tag", domain=m.DOMAINS["public-tag"])
    xk = det("x25519-pk-0", 32)
    for e in ep:
        f.add(g, "epoch %d (placeholder X25519 key)" % e, "valid", inputs={"x25519_key": xk.hex(), "epoch": e},
              output=m.public_tag(xk, e).hex())

    g = f.group(derivation="unsecured-tag", domain=m.DOMAINS["unsecured-tag"])
    nid = m.node_id(det("ed25519-pk-0", 32))
    for e in ep:
        f.add(g, "epoch %d" % e, "valid", inputs={"node_id": nid.hex(), "epoch": e},
              output=m.unsecured_tag(nid, e).hex())

    g = f.group(derivation="conversation-secret", domain=m.DOMAINS["conversation"])
    ni, nr = m.node_id(det("ed25519-pk-0", 32)), m.node_id(det("ed25519-pk-1", 32))
    seed = det("conversation-seed", 32)
    f.add(g, "initiator 0, responder 1", "valid",
          inputs={"seed": seed.hex(), "initiator_node_id": ni.hex(), "responder_node_id": nr.hex()},
          output=m.conversation_secret(seed, ni, nr).hex())
    f.add(g, "roles swapped give a different secret", "valid",
          inputs={"seed": seed.hex(), "initiator_node_id": nr.hex(), "responder_node_id": ni.hex()},
          output=m.conversation_secret(seed, nr, ni).hex())

    g = f.group(derivation="conversation-tag", domain=m.DOMAINS["conversation-tag"])
    cs = m.conversation_secret(seed, ni, nr)
    for d in (1, 2):
        for e in ep[:2]:
            f.add(g, "direction %d epoch %d" % (d, e), "valid",
                  inputs={"conversation_secret": cs.hex(), "direction": d, "epoch": e},
                  output=m.conversation_tag(cs, d, e).hex())

    g = f.group(derivation="lookup-tag", domain=m.DOMAINS["lookup-tag"])
    for i in range(3):
        salt = det("lookup-salt-%d" % i, 8)
        f.add(g, "salt %d" % i, "valid", inputs={"salt": salt.hex(), "node_id": nid.hex()},
              output=m.lookup_tag(salt, nid).hex())

    g = f.group(derivation="tombstone-commitment", domain=m.DOMAINS["tombstone"])
    for i in range(3):
        k = det("tombstone-k-%d" % i, 16)
        f.add(g, "k %d" % i, "valid", inputs={"k": k.hex()}, output=m.tombstone_commitment(k).hex())

    g = f.group(derivation="lookup-reply-key", domain=m.DOMAINS["lookup-reply"])
    salt = det("lookup-salt-0", 8)
    lt = m.lookup_tag(salt, nid)
    sh, ea, eb = det("x25519-shared", 32), det("eph-req", 32), det("eph-resp", 32)
    f.add(g, "HKDF step only; shared secret is placeholder bytes", "valid", [PLACEHOLDER],
          inputs={"shared_secret": sh.hex(), "salt": salt.hex(), "lookup_tag": lt.hex(),
                  "requester_ephemeral": ea.hex(), "responder_ephemeral": eb.hex()},
          output=m.lookup_reply_key(sh, salt, lt, ea, eb).hex())

    g = f.group(derivation="link-prologue", domain=m.DOMAINS["link-prologue"])
    for vs, sel, prof, mid in (([1], 1, 1, "Festival"), ([2, 1], 1, 2, "Field team 0")):
        dg = m.mesh_id_digest(mid, prof)
        f.add(g, "versions %s selected %d profile %d" % (vs, sel, prof), "valid",
              inputs={"versions": vs, "selected": sel, "profile": prof, "mesh_id_digest": dg.hex()},
              output=m.link_prologue(vs, sel, prof, dg).hex())

    g = f.group(derivation="e2e-prologue", domain=m.DOMAINS["e2e-prologue"])
    for prof in (1, 2):
        f.add(g, "profile %d" % prof, "valid", inputs={"version": 1, "profile": prof},
              output=m.e2e_prologue(1, prof).hex())

    g = f.group(derivation="hpke-info", domain=m.DOMAINS["hpke-first-message"])
    f.add(g, "sender 0 to recipient 1", "valid",
          inputs={"sender_static_key": dh("x25519-pk-0", 32), "recipient_node_id": nr.hex()},
          output=m.hpke_info(det("x25519-pk-0", 32), nr).hex())

    g = f.group(derivation="certificate-signing-input", domain=m.DOMAINS["certificate"])
    body = sample_certificate(1)[:-64]
    f.add(g, "classical certificate body (placeholder keys)", "valid", [PLACEHOLDER],
          inputs={"certificate_without_signature": body.hex()},
          output=m.certificate_signing_input(body).hex())

    g = f.group(derivation="broadcast-signing-input", domain=m.DOMAINS["broadcast"])
    fr = sample_broadcast_signed()[:-64]
    f.add(g, "signed broadcast without signature; hop limit byte removed", "valid", [PLACEHOLDER],
          inputs={"frame_without_signature": fr.hex()},
          output=m.broadcast_signing_input(fr).hex())
    f.write()
    return f


# ---------------------------------------------------------------------------
# Sample values
# ---------------------------------------------------------------------------

EXPIRY = 1798762200
MID = [det("message-id-%d" % i, 12).hex() for i in range(8)]
TAG = [det("tag-%d" % i, 8).hex() for i in range(8)]


def sample_certificate(profile, nb=1798761600, span=86400):
    f = {"version": 1, "profile": m.hx(profile),
         "ed25519_key": dh("ed25519-pk-0", 32), "x25519_key": dh("x25519-pk-0", 32),
         "not_before": nb, "not_after": nb + span, "signature": dh("placeholder-signature", 64)}
    if profile == 2:
        f["xwing_key"] = dh("xwing-pk-0", 1216)
    return m.encode_certificate(f)


def uhdr(t, **kw):
    f = {"type": m.hx(t), "hop_limit": 255, "message_id": MID[0], "expiry": EXPIRY,
         "destination_tag": TAG[0], "source_tag": TAG[1]}
    f.update(kw)
    return f


def fhdr(t, **kw):
    f = {"type": m.hx(t), "hop_limit": 64, "message_id": MID[1], "expiry": EXPIRY}
    f.update(kw)
    return f


def sample_broadcast_signed():
    return m.encode_frame(fhdr(0x80, container={
        "kind": "0x05", "origin_key": dh("ed25519-pk-0", 32), "payload": b"Water at gate 3".hex(),
        "signature": dh("placeholder-signature", 64)}))


# ---------------------------------------------------------------------------
# Layout files
# ---------------------------------------------------------------------------


def gen_records():
    f = File("records.json", "MeshLink/1 link record", "layout",
             {"Framing": "Body bytes are placeholders; in secured profiles they are Noise transport messages."})
    g = f.group(layout="record")
    for n in (1, 16, 1024):
        add_ok(f, g, "body of %d B" % n, m.u16(n) + det("record-%d" % n, n), ["Framing"])
    add_bad(f, g, "zero length", m.u16(0), "out-of-range", ["BadLength"])
    add_bad(f, g, "length 1,025 exceeds the record cap", m.u16(1025) + bytes(1025), "out-of-range", ["BadLength"])
    add_bad(f, g, "length says 16, body has 15", m.u16(16) + bytes(15), "truncated", ["Truncated"])
    add_bad(f, g, "length field cut", b"\x00", "truncated", ["Truncated"])
    add_bad(f, g, "one byte after the body", m.u16(2) + b"\x01\x02\x03", "trailing-bytes", ["TrailingBytes"])
    f.write()
    return f


def gen_advertisement():
    f = File("advertisement.json", "MeshLink/1 advertisement payload", "layout", {})
    g = f.group(layout="advertisement")
    base = {"layout_version": 1, "flags": 0, "mesh_tag": dh("adv-mesh", 4),
            "resolvable_tag": dh("adv-res", 8), "random": dh("adv-rand", 3)}
    for flags, c in ((0, "no flags"), (1, "COC_AVAILABLE"), (2, "PHY_2M"), (3, "COC_AVAILABLE and PHY_2M")):
        add_ok(f, g, c, m.encode_advertisement(dict(base, flags=flags)))
    add_ok(f, g, "reserved flag bit 4 set: ignored", m.encode_advertisement(dict(base, flags=0x13)),
           ["ReservedBits"], expect="acceptable")
    good = m.encode_advertisement(base)
    add_bad(f, g, "15 B", good[:15], "bad-length", ["BadLength"])
    add_bad(f, g, "17 B", good + b"\x00", "bad-length", ["BadLength"])
    for lv in (0, 2, 7):
        add_bad(f, g, "layout version %d" % lv, m.encode_advertisement(dict(base, layout_version=lv)),
                "unknown-version", ["UnknownVersion"])
    f.write()
    return f


def gen_capability():
    f = File("capability.json", "MeshLink/1 GATT capability record", "layout", {})
    g = f.group(layout="capability")
    psm = {"type": "0x01", "value": m.u16(0x0081).hex()}
    adv = {"type": "0x02", "value": dh("adv-full", 16)}
    add_ok(f, g, "version 1, no extensions", m.encode_capability({"versions": [{"version": 1}], "extensions": []}))
    add_ok(f, g, "version 1, PSM and advertisement",
           m.encode_capability({"versions": [{"version": 1}], "extensions": [psm, adv]}))
    add_ok(f, g, "versions 2 and 1, PSM",
           m.encode_capability({"versions": [{"version": 2}, {"version": 1}], "extensions": [psm]}), ["Upgrade"])
    add_ok(f, g, "unknown non-critical TLV 0x7F is ignored",
           m.encode_capability({"versions": [{"version": 1}], "extensions": [psm, {"type": "0x7F", "value": "00"}]}),
           ["UnknownTlv"])
    v1 = b"\x01\x01"
    add_bad(f, g, "zero versions", b"\x00", "out-of-range", ["BadLength"])
    add_bad(f, g, "nine versions", b"\x09" + bytes(range(9, 0, -1)), "out-of-range", ["BadLength"])
    add_bad(f, g, "version 0", b"\x01\x00", "out-of-range")
    add_bad(f, g, "versions ascending", b"\x02\x01\x02", "version-order")
    add_bad(f, g, "unknown critical TLV 0xF0", v1 + b"\xf0\x00\x00", "unknown-critical-tlv", ["UnknownTlv"])
    add_bad(f, g, "PSM with 3 B value", v1 + b"\x01\x00\x03\x00\x81\x00", "tlv-length", ["BadLength"])
    add_bad(f, g, "TLVs out of order", v1 + m.encode_tlvs([adv, psm]), "tlv-order")
    add_bad(f, g, "duplicate TLV", v1 + m.encode_tlvs([psm, psm]), "tlv-order")
    add_bad(f, g, "TLV value cut", v1 + b"\x01\x00\x02\x00", "truncated", ["Truncated"])
    add_bad(f, g, "record over 512 B", v1 + m.encode_tlvs([{"type": "0x7F", "value": "00" * 520}]), "too-long",
            ["BadLength"])
    f.write()
    return f


def gen_certificate():
    f = File("certificate.json", "MeshLink/1 identity certificate layout", "layout",
             {PLACEHOLDER: "Keys and signatures are placeholder bytes: only the framing is tested. "
                           "Signature vectors are tier 2."})
    g = f.group(layout="certificate")
    c1 = add_ok(f, g, "classical, 24 h validity", sample_certificate(1), [PLACEHOLDER])
    c2 = add_ok(f, g, "post-quantum, 24 h validity", sample_certificate(2), [PLACEHOLDER])
    assert len(c1) == m.L("certificate-classical-length") and len(c2) == m.L("certificate-post-quantum-length")
    add_ok(f, g, "classical, 1 s validity", sample_certificate(1, span=1), [PLACEHOLDER])
    for c, n in ((c1, "classical"), (c2, "post-quantum")):
        auto_mutations(f, g, n, c)
    add_bad(f, g, "version 2", b"\x02" + c1[1:], "unknown-version", ["UnknownVersion"])
    add_bad(f, g, "profile unsecured (0x00) has no certificate", c1[:1] + b"\x00" + c1[2:], "unknown-kind")
    add_bad(f, g, "profile 0x03", c1[:1] + b"\x03" + c1[2:], "unknown-kind")
    add_bad(f, g, "classical profile with post-quantum body", c2[:1] + b"\x01" + c2[2:], "trailing-bytes")
    add_bad(f, g, "validity 86,401 s", sample_certificate(1, span=86401), "out-of-range")
    add_bad(f, g, "not-after equals not-before", sample_certificate(1, span=0), "out-of-range")
    f.write()
    return f


def gen_handshake_payload():
    f = File("handshake-payload.json", "MeshLink/1 link handshake payload TLVs", "layout",
             {PLACEHOLDER: "Certificate bytes are placeholders; the payload is carried inside Noise (tier 2)."})
    g = f.group(layout="handshake-payload")
    cert = {"type": "0x81", "value": sample_certificate(1).hex()}
    rk = {"type": "0x02", "value": dh("resolving-key", 32) + m.u32(1798848000).hex()}
    add_ok(f, g, "empty payload (XX message 1)", b"")
    add_ok(f, g, "certificate only (open mesh)", m.encode_tlvs([cert]), [PLACEHOLDER])
    add_ok(f, g, "resolving key and certificate (gated mesh)", m.encode_tlvs([rk, cert]), [PLACEHOLDER])
    add_ok(f, g, "unknown non-critical TLV ignored", m.encode_tlvs([rk, {"type": "0x10", "value": "abcd"}, cert]),
           [PLACEHOLDER, "UnknownTlv"])
    add_bad(f, g, "unknown critical TLV", m.encode_tlvs([cert, {"type": "0x90", "value": ""}]),
            "unknown-critical-tlv", ["UnknownTlv"])
    add_bad(f, g, "certificate before resolving key", m.encode_tlvs([cert, rk]), "tlv-order")
    add_bad(f, g, "resolving key of 32 B", m.encode_tlvs([{"type": "0x02", "value": dh("resolving-key", 32)}]),
            "tlv-length", ["BadLength"])
    add_bad(f, g, "TLV header cut", b"\x81\x00", "truncated", ["Truncated"])
    f.write()
    return f


def gen_link_frames():
    f = File("link-frames.json", "MeshLink/1 link-local frames", "layout",
             {PLACEHOLDER: "Noise messages are placeholder bytes: only the framing is tested."})
    g = f.group(layout="frame")
    samples = [
        ("FRAGMENT first of a stream", {"type": "0x01", "stream_id": 3, "index": 0, "data": dh("frag", 1000)}),
        ("FRAGMENT last, index 5", {"type": "0x01", "stream_id": 3, "index": 0x8005, "data": dh("frag5", 10)}),
        ("HANDSHAKE step 1", {"type": "0x02", "version": 1, "step": 1, "noise_message": dh("xx1", 32)}),
        ("HANDSHAKE step 2", {"type": "0x02", "version": 1, "step": 2, "noise_message": dh("xx2", 240)}),
        ("PLAIN_HELLO", {"type": "0x03", "version": 1, "profile": 0,
                         "mesh_id_digest": m.mesh_id_digest("Picnic", 0).hex(), "node_id": dh("nid", 32)}),
        ("CLOSE duplicate link", {"type": "0x04", "reason": 1}),
        ("CLOSE unknown reason (treated as NORMAL)", {"type": "0x04", "reason": 0x7F}),
        ("REKEY", {"type": "0x05"}),
        ("BUSY", {"type": "0x06", "stream_id": 9, "reason": 1}),
        ("NEIGHBOR_LIST empty", {"type": "0x07", "own_id": TAG[2], "next_id": TAG[6], "neighbors": []}),
        ("NEIGHBOR_LIST three entries", {"type": "0x07", "own_id": TAG[2], "next_id": TAG[6],
                                         "neighbors": [{"id": TAG[i]} for i in (3, 4, 5)]}),
        ("SHUFFLE", {"type": "0x08", "entries": [{"id": TAG[i]} for i in range(5)]}),
        ("SHUFFLE_REPLY empty", {"type": "0x09", "entries": []}),
        ("CATCH_UP empty", {"type": "0x0A", "offered": [], "held": [], "tombstones": []}),
        ("CATCH_UP all sections", {"type": "0x0A", "offered": [{"message_id": MID[2]}],
                                   "held": [{"message_id": MID[3], "destination_tag": TAG[6]}],
                                   "tombstones": [{"message_id": MID[4], "k": dh("tombstone-k-0", 16)}]}),
        ("IHAVE two IDs", {"type": "0x0B", "message_ids": [{"message_id": MID[5]}, {"message_id": MID[6]}]}),
        ("GRAFT", {"type": "0x0C", "message_id": MID[5]}),
        ("PRUNE", {"type": "0x0D"}),
        ("RESOLVING_KEY_UPDATE", {"type": "0x0E", "resolving_key": dh("resolving-key-next", 32),
                                  "not_after": 1798934400}),
        ("BEARER_SWITCH", {"type": "0x0F"}),
    ]
    enc = {}
    for c, fr in samples:
        flags = [PLACEHOLDER] if fr["type"] == "0x02" else []
        enc[c] = add_ok(f, g, c, m.encode_frame(fr), flags)
    for c, data in enc.items():
        auto_mutations(f, g, c, data)
    max_catch = {"type": "0x0A", "offered": [{"message_id": MID[0]}] * m.L("catch-up-offered-max"),
                 "held": [{"message_id": MID[0], "destination_tag": TAG[0]}] * m.L("catch-up-held-max"),
                 "tombstones": [{"message_id": MID[0], "k": "00" * 16}] * m.L("catch-up-tombstones-max")}
    data = add_ok(f, g, "CATCH_UP at every section cap", m.encode_frame(max_catch), ["Limit"])
    assert len(data) == 7127, len(data)
    add_bad(f, g, "unknown link-local type 0x3F", b"\x3f\x00", "unknown-type", ["UnknownType"])
    add_bad(f, g, "reserved type 0x00", b"\x00", "reserved-type", ["UnknownType"])
    add_bad(f, g, "reserved type 0xC0", b"\xc0" + bytes(17), "reserved-type", ["UnknownType"])
    add_bad(f, g, "reserved type 0xFF", b"\xff", "reserved-type", ["UnknownType"])
    add_bad(f, g, "FRAGMENT with no data", b"\x01\x03\x00\x00", "out-of-range", ["BadLength"])
    add_bad(f, g, "FRAGMENT data larger than a record", b"\x01\x03\x00\x00" + bytes(1021), "out-of-range",
            ["BadLength"])
    add_bad(f, g, "HANDSHAKE step 4", b"\x02\x01\x04" + bytes(32), "out-of-range")
    add_bad(f, g, "HANDSHAKE version 0", b"\x02\x00\x01" + bytes(32), "out-of-range")
    add_bad(f, g, "HANDSHAKE message over 4,096 B", b"\x02\x01\x01" + bytes(4097), "out-of-range", ["BadLength"])
    add_bad(f, g, "HANDSHAKE with empty message", b"\x02\x01\x01", "out-of-range", ["BadLength"])
    add_bad(f, g, "PLAIN_HELLO with classical profile", b"\x03\x01\x01" + bytes(64), "out-of-range")
    add_bad(f, g, "NEIGHBOR_LIST with 33 entries", b"\x07" + bytes(16) + b"\x21" + bytes(8 * 33), "out-of-range",
            ["BadLength"])
    add_bad(f, g, "NEIGHBOR_LIST count says 2, one entry", b"\x07" + bytes(16) + b"\x02" + bytes(8), "truncated",
            ["Truncated"])
    add_bad(f, g, "SHUFFLE with no entries", b"\x08\x00", "out-of-range", ["BadLength"])
    add_bad(f, g, "SHUFFLE with 6 entries", b"\x08\x06" + bytes(48), "out-of-range", ["BadLength"])
    add_bad(f, g, "CATCH_UP with 129 offered IDs", b"\x0a\x00\x81" + bytes(12 * 129) + bytes(4), "out-of-range",
            ["BadLength"])
    add_bad(f, g, "IHAVE with zero IDs", b"\x0b\x00", "out-of-range", ["BadLength"])
    add_bad(f, g, "IHAVE with 33 IDs", b"\x0b\x21" + bytes(12 * 33), "out-of-range", ["BadLength"])
    f.write()
    return f


def gen_routed_frames():
    f = File("routed-frames.json", "MeshLink/1 routed frames", "layout",
             {PLACEHOLDER: "Noise messages, ciphertexts, keys and signatures are placeholder bytes: "
                           "only the framing is tested."})
    g = f.group(layout="frame")
    tomb = [{"message_id": MID[0], "k": dh("tombstone-k-0", 16)}]
    commit = m.tombstone_commitment(det("tombstone-k-0", 16)).hex()
    samples = [
        ("MESSAGE, IK initiator container", uhdr(0x40, commitment=commit, container={
            "kind": "0x01", "noise_message": dh("ik1", 160)})),
        ("MESSAGE, transport container", uhdr(0x40, hop_limit=7, commitment=commit, container={
            "kind": "0x03", "nonce": 41, "ciphertext": dh("ct", 80)})),
        ("MESSAGE, unsecured container", uhdr(0x40, commitment=commit, container={
            "kind": "0x04", "sender_node_id": dh("nid", 32), "plaintext": b"hello".hex()})),
        ("DELIVERY_RECEIPT, one tombstone, transport", uhdr(0x41, tombstones=tomb, container={
            "kind": "0x03", "nonce": 3, "ciphertext": dh("ct-r", 48)})),
        ("DELIVERY_RECEIPT, two tombstones, IK responder", uhdr(0x41, tombstones=tomb + [
            {"message_id": MID[1], "k": dh("tombstone-k-1", 16)}], container={
            "kind": "0x02", "noise_message": dh("ik2r", 96)})),
        ("READ_RECEIPT, transport", uhdr(0x42, container={"kind": "0x03", "nonce": 9, "ciphertext": dh("ct-rr", 48)})),
        ("READ_RECEIPT, IK initiator (new session)", uhdr(0x42, container={
            "kind": "0x01", "noise_message": dh("ik1-rr", 160)})),
        ("ROUTE_REPLY", uhdr(0x43, target_tag=TAG[4], target_sequence=17, hop_count=3)),
        ("LOOKUP_REPLY, classical certificate", uhdr(0x44, destination_tag=dh("lookup-salt-0", 8),
                                                    ephemeral_key=dh("eph-resp", 32),
                                                    sealed_certificate=dh("sealed", 154))),
        ("LOOKUP_REPLY, post-quantum certificate", uhdr(0x44, destination_tag=dh("lookup-salt-0", 8),
                                                       ephemeral_key=dh("eph-resp", 32),
                                                       sealed_certificate=dh("sealed-pq", 1370))),
        ("BROADCAST, signed", None),
        ("BROADCAST, unsecured", fhdr(0x80, container={"kind": "0x04", "sender_node_id": dh("nid", 32),
                                                       "plaintext": b"Lost dog, black".hex()})),
        ("ROUTE_REQUEST", fhdr(0x81, target_tag=TAG[4], origin_tag=TAG[5], origin_sequence=1,
                               target_sequence=0, hop_count=0)),
        ("LOOKUP_REQUEST", fhdr(0x82, salt=dh("lookup-salt-0", 8),
                                lookup_tag=m.lookup_tag(det("lookup-salt-0", 8),
                                                        m.node_id(det("ed25519-pk-0", 32))).hex(),
                                ephemeral_key=dh("eph-req", 32))),
    ]
    enc = {}
    for c, fr in samples:
        if fr is None:
            data = sample_broadcast_signed()
        else:
            data = m.encode_frame(fr)
        enc[c] = add_ok(f, g, c, data, [PLACEHOLDER])
    for c, data in enc.items():
        auto_mutations(f, g, c, data)
    rq = enc["ROUTE_REQUEST"]
    assert len(rq) == 18 + 25 and len(enc["LOOKUP_REQUEST"]) == 18 + 48
    add_ok(f, g, "unknown unicast type 0x7E: relayed by header, not delivered",
           m.encode_frame(uhdr(0x7E, body="0102")), ["UnknownType"], expect="acceptable")
    add_ok(f, g, "unknown flood type 0xBF: relayed by header, not delivered",
           m.encode_frame(fhdr(0xBF, body="")), ["UnknownType"], expect="acceptable")
    add_bad(f, g, "unknown unicast type with cut header", b"\x7e\x05" + bytes(10), "truncated",
            ["UnknownType", "Truncated"])
    msg = enc["MESSAGE, transport container"]
    add_bad(f, g, "hop limit 0", msg[:1] + b"\x00" + msg[2:], "out-of-range")
    add_bad(f, g, "MESSAGE container kind 0x05", msg[:50] + b"\x05" + msg[51:], "unknown-kind")
    add_bad(f, g, "MESSAGE container kind 0x00", msg[:50] + b"\x00" + msg[51:], "unknown-kind")
    add_bad(f, g, "MESSAGE with IK responder container", msg[:50] + b"\x02" + m.u32(4) + bytes(4),
            "unknown-kind")
    rr = enc["READ_RECEIPT, transport"]
    add_bad(f, g, "READ_RECEIPT with IK responder container", rr[:34] + b"\x02" + m.u32(4) + bytes(4),
            "unknown-kind")
    rc = enc["DELIVERY_RECEIPT, one tombstone, transport"]
    add_bad(f, g, "DELIVERY_RECEIPT with IK initiator container", rc[:63] + b"\x01" + m.u32(4) + bytes(4),
            "unknown-kind")
    add_bad(f, g, "DELIVERY_RECEIPT with zero tombstones", rc[:34] + b"\x00" + rc[63:], "out-of-range",
            ["BadLength"])
    add_bad(f, g, "DELIVERY_RECEIPT with 33 tombstones", rc[:34] + b"\x21" + bytes(28 * 33) + rc[63:],
            "out-of-range", ["BadLength"])
    add_bad(f, g, "transport ciphertext shorter than the AEAD tag", msg[:51] + m.u64(1) + m.u32(16) + bytes(16),
            "out-of-range", ["BadLength"])
    add_bad(f, g, "ciphertext length says 80, 79 present", msg[:-1], "truncated", ["Truncated"])
    bs = sample_broadcast_signed()
    big = fhdr(0x80, container={"kind": "0x04", "sender_node_id": dh("nid", 32), "plaintext": "00" * 1025})
    add_bad(f, g, "BROADCAST payload 1,025 B", m.encode_frame(big), "out-of-range", ["BadLength"])
    add_bad(f, g, "BROADCAST container kind 0x03", bs[:18] + b"\x03" + bs[19:], "unknown-kind")
    add_bad(f, g, "ROUTE_REQUEST origin sequence 0", rq[:34] + m.u32(0) + rq[38:], "out-of-range")
    lr = enc["LOOKUP_REPLY, classical certificate"]
    add_bad(f, g, "LOOKUP_REPLY sealed certificate of 153 B", lr[:66] + m.u16(153) + bytes(153), "out-of-range",
            ["BadLength"])
    f.write()
    return f


def gen_inner():
    f = File("inner-plaintext.json", "MeshLink/1 end-to-end plaintext", "layout",
             {"Framing": "The plaintext that Noise or HPKE encrypts, or that kind 0x04 carries in clear."})
    seed = {"type": "0x82", "value": dh("conversation-seed", 32)}
    rr = {"type": "0x04", "value": ""}
    cert = {"type": "0x01", "value": sample_certificate(1).hex()}
    ids = {"type": "0x83", "value": MID[0] + MID[1]}
    for secured in (True, False):
        g = f.group(layout="inner-message", secured=secured)
        msg = {"message_id": MID[0], "expiry": EXPIRY, "k": dh("tombstone-k-0", 16), "extensions": [],
               "payload": b"hi".hex()}
        a = add_ok(f, g, "short message", m.encode_inner(msg, "message", secured))
        add_ok(f, g, "first message with seed, receipt request and certificate",
               m.encode_inner(dict(msg, extensions=[cert, rr, seed]), "message", secured), [PLACEHOLDER])
        if secured:
            add_ok(f, g, "content already a multiple of 32 B", m.encode_inner(dict(msg, payload="00" * 28),
                                                                              "message", True), ["Padding"])
            add_bad(f, g, "non-zero padding byte", a[:-1] + b"\x01", "bad-padding", ["Padding"])
            add_bad(f, g, "32 B of padding", a + bytes(32), "bad-padding", ["Padding"])
            add_bad(f, g, "total not a multiple of 32", a[:-1], "bad-padding", ["Padding"])
        else:
            add_bad(f, g, "padding in unsecured", a + b"\x00", "bad-padding", ["Padding"])
        add_bad(f, g, "content length beyond the buffer", m.u32(4096) + bytes(28), "truncated", ["Truncated"])
        add_bad(f, g, "unknown critical TLV", m.encode_inner(dict(msg, extensions=[{"type": "0xA0", "value": ""}]),
                                                             "message", secured), "unknown-critical-tlv",
                ["UnknownTlv"])
        add_bad(f, g, "conversation seed of 16 B",
                m.encode_inner(dict(msg, extensions=[{"type": "0x82", "value": "00" * 16}]), "message", secured),
                "tlv-length", ["BadLength"])
        g = f.group(layout="inner-receipt", secured=secured)
        rc = {"message_id": MID[2], "expiry": EXPIRY, "extensions": [ids]}
        add_ok(f, g, "receipt listing two message IDs", m.encode_inner(rc, "receipt", secured))
        body = m.encode_fields(rc, m.INNER["receipt"]) + b"\x00"
        raw = m.u32(len(body)) + body
        if secured:
            raw += bytes(-len(raw) % 32)
        add_bad(f, g, "trailing byte inside the content", raw, "trailing-bytes", ["TrailingBytes"])
    f.write()
    return f


def main():
    files = [gen_derivations(), gen_records(), gen_advertisement(), gen_capability(), gen_certificate(),
             gen_handshake_payload(), gen_link_frames(), gen_routed_frames(), gen_inner()]
    for fl in files:
        print("%-24s %3d tests" % (fl.name, fl.count))


if __name__ == "__main__":
    main()
