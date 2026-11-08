"""Reference codec and derivations for MeshLink protocol 1 tier-1 vectors.

Python 3 standard library only. Shared by gen_vectors.py and check_spec.py.
The normative text is docs/spec/*.md; this module mirrors it so that the
vectors can be generated and re-checked. Data files in docs/spec/data are
authoritative for numbers and codes; this module loads them.
"""

import hashlib
import hmac
import json
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPEC = os.path.join(ROOT, "docs", "spec")
DATA = os.path.join(SPEC, "data")
VECTORS = os.path.join(SPEC, "vectors")


def load_data(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return json.load(f)


def _limits():
    return {e["name"]: e["value"] for e in load_data("limits.json")["entries"]}


def _domains():
    return {e["purpose"]: e["string"] for e in load_data("domain-strings.json")["entries"]}


LIMITS = _limits()
DOMAINS = _domains()


def L(name):
    return LIMITS[name]


# ---------------------------------------------------------------------------
# Derivations (README "Derivation rules", D-01)
# ---------------------------------------------------------------------------


def ds(purpose):
    """Encoded domain string: u8 length || ASCII bytes."""
    s = DOMAINS[purpose].encode("ascii")
    if not 1 <= len(s) <= 255:
        raise ValueError("domain string length")
    return bytes([len(s)]) + s


def u8(v):
    return struct.pack(">B", v)


def u16(v):
    return struct.pack(">H", v)


def u32(v):
    return struct.pack(">I", v)


def u64(v):
    return struct.pack(">Q", v)


def h(purpose, *parts, key=b"", out=32):
    """BLAKE2b-256 over DS || parts, optionally keyed, truncated to `out` bytes."""
    d = hashlib.blake2b(ds(purpose) + b"".join(parts), digest_size=32, key=key).digest()
    return d[:out]


def hkdf(ikm, salt, purpose, context=b"", length=32):
    """HKDF-SHA-256 (RFC 5869) with info = DS || context."""
    if not salt:
        salt = bytes(32)
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    info = ds(purpose) + context
    okm, t, i = b"", b"", 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        okm += t
        i += 1
    return okm[:length]


def epoch_of(unix_seconds):
    return unix_seconds // L("epoch-length")


def node_id(ed25519_pk):
    return h("node-id", ed25519_pk)


def mesh_id_digest(mesh_id, profile):
    b = mesh_id.encode("utf-8")
    if not L("mesh-id-min-length") <= len(b) <= L("mesh-id-max-length"):
        raise ValueError("mesh id length")
    return h("mesh-id", u16(len(b)), b, u8(profile))


def mesh_tag_key(digest, mesh_key=None):
    if mesh_key is None:
        return digest
    return hkdf(mesh_key, digest, "mesh-tag-key")


def mesh_tag(tag_key, epoch):
    return h("mesh-tag", u32(epoch), key=tag_key, out=4)


def mesh_psk(mesh_key, digest):
    return hkdf(mesh_key, digest, "mesh-psk")


def resolvable_tag(resolving_key, epoch):
    return h("resolvable-tag", u32(epoch), key=resolving_key, out=8)


def public_tag(x25519_pk, epoch):
    return h("public-tag", x25519_pk, u32(epoch), out=8)


def unsecured_tag(nid, epoch):
    return h("unsecured-tag", nid, u32(epoch), out=8)


def conversation_secret(seed, nid_initiator, nid_responder):
    return hkdf(seed, b"", "conversation", nid_initiator + nid_responder)


def conversation_tag(secret, direction, epoch):
    return h("conversation-tag", u8(direction), u32(epoch), key=secret, out=8)


def lookup_tag(salt, nid):
    return h("lookup-tag", salt, nid, out=8)


def tombstone_commitment(k):
    return h("tombstone", k, out=16)


def lookup_reply_key(shared, salt, ltag, eph_req, eph_resp):
    return hkdf(shared, salt + ltag, "lookup-reply", eph_req + eph_resp)


def link_prologue(versions, selected, profile, digest):
    return ds("link-prologue") + u8(len(versions)) + bytes(versions) + u8(selected) + u8(profile) + digest


def e2e_prologue(version, profile):
    return ds("e2e-prologue") + u8(version) + u8(profile)


def certificate_signing_input(body):
    return ds("certificate") + body


def broadcast_signing_input(frame_without_sig):
    # Excludes the hop limit (byte 1), which relays change.
    return ds("broadcast") + frame_without_sig[:1] + frame_without_sig[2:]


def hpke_info(sender_static_key, nid_recipient):
    return ds("hpke-first-message") + sender_static_key + nid_recipient


# ---------------------------------------------------------------------------
# Generic codec (README "Conventions")
# ---------------------------------------------------------------------------


class DecodeError(Exception):
    """Raised with one of the error codes listed in vectors/README.md."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def hx(code):
    return "0x%02X" % code


def _tlv_registry():
    reg = {}
    for e in load_data("tlv-types.json")["entries"]:
        reg[(e["context"], int(e["code"], 16))] = e
    return reg


TLVS = _tlv_registry()


class Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, n):
        if self.p + n > len(self.d):
            raise DecodeError("truncated")
        b = self.d[self.p:self.p + n]
        self.p += n
        return b

    def rest(self):
        b = self.d[self.p:]
        self.p = len(self.d)
        return b

    def left(self):
        return len(self.d) - self.p


INT_SIZES = {"u8": 1, "u16": 2, "u32": 4, "u64": 8}


def _check_range(v, lo, hi):
    if (lo is not None and v < lo) or (hi is not None and v > hi):
        raise DecodeError("out-of-range")


def decode_tlvs(r, context):
    out, last = [], -1
    while r.left():
        t = r.take(1)[0]
        n = struct.unpack(">H", r.take(2))[0]
        v = r.take(n)
        if t <= last:
            raise DecodeError("tlv-order")
        last = t
        e = TLVS.get((context, t))
        if e is None:
            if t & 0x80:
                raise DecodeError("unknown-critical-tlv")
        elif e["length"] is not None and n != e["length"]:
            raise DecodeError("tlv-length")
        out.append({"type": hx(t), "value": v.hex()})
    return out


def encode_tlvs(items):
    b = b""
    for it in items:
        v = bytes.fromhex(it["value"])
        b += u8(int(it["type"], 16)) + u16(len(v)) + v
    return b


def decode_fields(r, fields):
    out = {}
    for f in fields:
        k, name = f[0], f[1]
        if k in INT_SIZES:
            v = int.from_bytes(r.take(INT_SIZES[k]), "big")
            _check_range(v, f[2] if len(f) > 2 else None, f[3] if len(f) > 3 else None)
            out[name] = v
        elif k == "bytes":
            out[name] = r.take(f[2]).hex()
        elif k == "vbytes":
            n = int.from_bytes(r.take(f[2]), "big")
            _check_range(n, f[3], f[4])
            out[name] = r.take(n).hex()
        elif k == "rest":
            v = r.rest()
            _check_range(len(v), f[2], f[3])
            out[name] = v.hex()
        elif k == "list":
            n = int.from_bytes(r.take(f[2]), "big")
            _check_range(n, f[3], f[4])
            out[name] = [decode_fields(r, f[5]) for _ in range(n)]
        elif k == "tlvs":
            out[name] = decode_tlvs(r, f[2])
        elif k == "tlvblock":
            n = struct.unpack(">H", r.take(2))[0]
            out[name] = decode_tlvs(Reader(r.take(n)), f[2])
        elif k == "union":
            kind = r.take(1)[0]
            if kind not in f[2]:
                raise DecodeError("unknown-kind")
            sub = decode_fields(r, f[2][kind])
            out[name] = dict({"kind": hx(kind)}, **sub)
        else:
            raise AssertionError(k)
    return out


def encode_fields(values, fields):
    b = b""
    for f in fields:
        k, name = f[0], f[1]
        v = values[name]
        if k in INT_SIZES:
            b += int(v).to_bytes(INT_SIZES[k], "big")
        elif k == "bytes":
            b += bytes.fromhex(v)
        elif k == "vbytes":
            raw = bytes.fromhex(v)
            b += len(raw).to_bytes(f[2], "big") + raw
        elif k == "rest":
            b += bytes.fromhex(v)
        elif k == "list":
            b += len(v).to_bytes(f[2], "big") + b"".join(encode_fields(x, f[5]) for x in v)
        elif k == "tlvs":
            b += encode_tlvs(v)
        elif k == "tlvblock":
            t = encode_tlvs(v)
            b += u16(len(t)) + t
        elif k == "union":
            kind = int(v["kind"], 16)
            b += u8(kind) + encode_fields(v, f[2][kind])
        else:
            raise AssertionError(k)
    return b


# ---------------------------------------------------------------------------
# Layouts (link.md, routing.md, end-to-end.md, discovery.md, identity.md)
# ---------------------------------------------------------------------------

ID8 = [("bytes", "id", 8)]
MSG_ID = [("bytes", "message_id", 12)]
TOMBSTONE = [("bytes", "message_id", 12), ("bytes", "k", 16)]


def _frame_types():
    out = {}
    for e in load_data("frame-types.json")["entries"]:
        out[int(e["code"], 16)] = e
    return out


FRAME_TYPES = _frame_types()


def _ft(name):
    for c, e in FRAME_TYPES.items():
        if e["name"] == name:
            return c
    raise KeyError(name)


def _containers(kinds, payload_max):
    fmax = L("frame-max")
    all_kinds = {
        0x01: [("vbytes", "noise_message", 4, 1, fmax)],
        0x02: [("vbytes", "noise_message", 4, 1, fmax)],
        0x03: [("u64", "nonce"), ("vbytes", "ciphertext", 4, 17, fmax)],
        0x04: [("bytes", "sender_node_id", 32), ("vbytes", "plaintext", 4, 0, payload_max)],
        0x05: [("bytes", "origin_key", 32), ("vbytes", "payload", 2, 0, payload_max),
               ("bytes", "signature", 64)],
    }
    return {k: all_kinds[k] for k in kinds}


UNICAST_HEADER = [("u8", "hop_limit", 1, 255), ("bytes", "message_id", 12), ("u32", "expiry"),
                  ("bytes", "destination_tag", 8), ("bytes", "source_tag", 8)]
FLOOD_HEADER = [("u8", "hop_limit", 1, 255), ("bytes", "message_id", 12), ("u32", "expiry")]


def frame_layouts():
    fmax = L("frame-max")
    rb = L("record-body-max")
    return {
        _ft("FRAGMENT"): [("u8", "stream_id"), ("u16", "index"), ("rest", "data", 1, rb - 4)],
        _ft("HANDSHAKE"): [("u8", "version", 1, 255), ("u8", "step", 1, 3),
                           ("rest", "noise_message", 1, L("handshake-message-max"))],
        _ft("PLAIN_HELLO"): [("u8", "version", 1, 255), ("u8", "profile", 0, 0),
                             ("bytes", "mesh_id_digest", 32), ("bytes", "node_id", 32)],
        _ft("CLOSE"): [("u8", "reason")],
        _ft("REKEY"): [],
        _ft("BUSY"): [("u8", "stream_id"), ("u8", "reason")],
        _ft("NEIGHBOR_LIST"): [("bytes", "own_id", 8), ("bytes", "next_id", 8),
                               ("list", "neighbors", 1, 0, L("neighbor-list-max"), ID8)],
        _ft("SHUFFLE"): [("list", "entries", 1, 1, L("shuffle-entries-max"), ID8)],
        _ft("SHUFFLE_REPLY"): [("list", "entries", 1, 0, L("shuffle-entries-max"), ID8)],
        _ft("CATCH_UP"): [
            ("list", "offered", 2, 0, L("catch-up-offered-max"), MSG_ID),
            ("list", "held", 2, 0, L("catch-up-held-max"),
             [("bytes", "message_id", 12), ("bytes", "destination_tag", 8)]),
            ("list", "tombstones", 2, 0, L("catch-up-tombstones-max"), TOMBSTONE)],
        _ft("IHAVE"): [("list", "message_ids", 1, 1, L("ihave-ids-max"), MSG_ID)],
        _ft("GRAFT"): [("bytes", "message_id", 12)],
        _ft("PRUNE"): [],
        _ft("RESOLVING_KEY_UPDATE"): [("bytes", "resolving_key", 32), ("u32", "not_after")],
        _ft("BEARER_SWITCH"): [],
        _ft("MESSAGE"): UNICAST_HEADER + [
            ("bytes", "commitment", 16),
            ("union", "container", _containers([1, 3, 4], fmax))],
        _ft("DELIVERY_RECEIPT"): UNICAST_HEADER + [
            ("list", "tombstones", 1, 1, L("receipt-batch-max"), TOMBSTONE),
            ("union", "container", _containers([2, 3, 4], fmax))],
        _ft("READ_RECEIPT"): UNICAST_HEADER + [
            ("union", "container", _containers([1, 3, 4], fmax))],
        _ft("ROUTE_REPLY"): UNICAST_HEADER + [
            ("bytes", "target_tag", 8), ("u32", "target_sequence", 1, None), ("u8", "hop_count")],
        _ft("LOOKUP_REPLY"): UNICAST_HEADER + [
            ("bytes", "ephemeral_key", 32),
            ("vbytes", "sealed_certificate", 2, L("certificate-classical-length") + 16,
             L("certificate-post-quantum-length") + 16)],
        _ft("BROADCAST"): FLOOD_HEADER + [
            ("union", "container", _containers([4, 5], L("broadcast-payload-max")))],
        _ft("ROUTE_REQUEST"): FLOOD_HEADER + [
            ("bytes", "target_tag", 8), ("bytes", "origin_tag", 8),
            ("u32", "origin_sequence", 1, None), ("u32", "target_sequence"), ("u8", "hop_count")],
        _ft("LOOKUP_REQUEST"): FLOOD_HEADER + [
            ("bytes", "salt", 8), ("bytes", "lookup_tag", 8), ("bytes", "ephemeral_key", 32)],
    }


FRAMES = frame_layouts()


def frame_class(code):
    if 0x01 <= code <= 0x3F:
        return "link-local"
    if 0x40 <= code <= 0x7F:
        return "unicast"
    if 0x80 <= code <= 0xBF:
        return "flood"
    return "reserved"


def decode_frame(data):
    """Returns (fields, result). result is "valid" or "acceptable" (unknown routed type)."""
    if not data:
        raise DecodeError("truncated")
    r = Reader(data)
    t = r.take(1)[0]
    cls = frame_class(t)
    if cls == "reserved":
        raise DecodeError("reserved-type")
    if t in FRAMES:
        out = {"type": hx(t)}
        out.update(decode_fields(r, FRAMES[t]))
        result = "valid"
    elif cls == "link-local":
        raise DecodeError("unknown-type")
    else:
        hdr = UNICAST_HEADER if cls == "unicast" else FLOOD_HEADER
        out = {"type": hx(t)}
        out.update(decode_fields(r, hdr + [("rest", "body", 0, None)]))
        result = "acceptable"
    if r.left():
        raise DecodeError("trailing-bytes")
    if len(data) > L("frame-max"):
        raise DecodeError("too-long")
    return out, result


def encode_frame(fields):
    t = int(fields["type"], 16)
    if t in FRAMES:
        layout = FRAMES[t]
    else:
        hdr = UNICAST_HEADER if frame_class(t) == "unicast" else FLOOD_HEADER
        layout = hdr + [("rest", "body", 0, None)]
    return u8(t) + encode_fields(fields, layout)


def decode_record(data):
    r = Reader(data)
    out = decode_fields(r, [("vbytes", "body", 2, L("record-body-min"), L("record-body-max"))])
    if r.left():
        raise DecodeError("trailing-bytes")
    return out


def encode_record(fields):
    b = bytes.fromhex(fields["body"])
    return u16(len(b)) + b


def decode_advertisement(data):
    if len(data) != L("advertisement-length"):
        raise DecodeError("bad-length")
    layout = data[0] >> 5
    if layout != L("advertisement-layout-version"):
        raise DecodeError("unknown-version")
    flags = data[0] & 0x1F
    out = {"layout_version": layout, "flags": flags, "mesh_tag": data[1:5].hex(),
           "resolvable_tag": data[5:13].hex(), "random": data[13:16].hex()}
    return out, ("acceptable" if flags & ~ADVERT_KNOWN_FLAGS else "valid")


def _advert_known_flags():
    m = 0
    for e in load_data("codes.json")["entries"]:
        if e["registry"] == "advertisement-flag":
            m |= 1 << int(e["code"], 16)
    return m


ADVERT_KNOWN_FLAGS = _advert_known_flags()


def encode_advertisement(f):
    return (u8((f["layout_version"] << 5) | f["flags"]) + bytes.fromhex(f["mesh_tag"])
            + bytes.fromhex(f["resolvable_tag"]) + bytes.fromhex(f["random"]))


CAPABILITY = [("list", "versions", 1, 1, L("versions-max"), [("u8", "version", 1, 255)]),
              ("tlvs", "extensions", "capability")]


def decode_capability(data):
    if len(data) > L("capability-record-max"):
        raise DecodeError("too-long")
    out = decode_fields(Reader(data), CAPABILITY)
    vs = [v["version"] for v in out["versions"]]
    if any(a <= b for a, b in zip(vs, vs[1:])):
        raise DecodeError("version-order")
    return out


def encode_capability(f):
    return encode_fields(f, CAPABILITY)


CERT_KEYS = [("bytes", "ed25519_key", 32), ("bytes", "x25519_key", 32)]
CERT_TAIL = [("u32", "not_before"), ("u32", "not_after"), ("bytes", "signature", 64)]
CERT_PROFILES = {0x01: CERT_KEYS + CERT_TAIL,
                 0x02: CERT_KEYS + [("bytes", "xwing_key", 1216)] + CERT_TAIL}


def decode_certificate(data):
    r = Reader(data)
    v = r.take(1)[0]
    if v != L("certificate-version"):
        raise DecodeError("unknown-version")
    p = r.take(1)[0]
    if p not in CERT_PROFILES:
        raise DecodeError("unknown-kind")
    out = {"version": v, "profile": hx(p)}
    out.update(decode_fields(r, CERT_PROFILES[p]))
    if r.left():
        raise DecodeError("trailing-bytes")
    span = out["not_after"] - out["not_before"]
    if span <= 0 or span > L("certificate-validity-max"):
        raise DecodeError("out-of-range")
    return out


def encode_certificate(f):
    p = int(f["profile"], 16)
    return u8(f["version"]) + u8(p) + encode_fields(f, CERT_PROFILES[p])


def decode_handshake_payload(data):
    return {"tlvs": decode_tlvs(Reader(data), "handshake")}


def encode_handshake_payload(f):
    return encode_tlvs(f["tlvs"])


INNER = {
    "message": [("bytes", "message_id", 12), ("u32", "expiry"), ("bytes", "k", 16),
                ("tlvblock", "extensions", "e2e"), ("rest", "payload", 0, L("unicast-payload-max"))],
    "receipt": [("bytes", "message_id", 12), ("u32", "expiry"), ("tlvblock", "extensions", "e2e")],
}


def decode_inner(data, content, secured):
    r = Reader(data)
    n = struct.unpack(">I", r.take(4))[0]
    body = r.take(n)
    pad = r.rest()
    block = L("padding-block")
    if secured:
        if len(data) % block or len(pad) >= block or any(pad):
            raise DecodeError("bad-padding")
    elif pad:
        raise DecodeError("bad-padding")
    cr = Reader(body)
    out = decode_fields(cr, INNER[content])
    if cr.left():
        raise DecodeError("trailing-bytes")
    return out


def encode_inner(f, content, secured):
    body = encode_fields(f, INNER[content])
    b = u32(len(body)) + body
    if secured:
        b += bytes(-len(b) % L("padding-block"))
    return b
