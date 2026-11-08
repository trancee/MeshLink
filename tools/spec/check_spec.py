#!/usr/bin/env python3
"""Check the MeshLink protocol 1 spec for internal consistency.

Python 3 standard library only. Exits non-zero on any failure. Checks:

1. Data files and vector files match the basic shape of their JSON Schemas.
2. Data registries are self-consistent (unique keys, code ranges, critical bits).
3. Every Markdown table preceded by `<!-- data: FILE [field=value] -->` agrees
   with the data file, and every data entry appears in at least one table.
4. Every `[D-nn]` used in the spec exists in decisions.md and vice versa.
5. Every tier-1 vector is recomputed with the reference codec and derivations.
"""

import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mlspec as m  # noqa: E402

ERRORS = []


def fail(msg):
    ERRORS.append(msg)


def rel(p):
    return os.path.relpath(p, m.ROOT)


# ---------------------------------------------------------------------------
# 1. Minimal JSON Schema (draft 2020-12 subset) validation
# ---------------------------------------------------------------------------

TYPES = {"object": dict, "array": list, "string": str, "boolean": bool, "null": type(None)}


def _type_ok(v, t):
    if t == "integer":
        return isinstance(v, int) and not isinstance(v, bool)
    if t == "number":
        return isinstance(v, (int, float)) and not isinstance(v, bool)
    return isinstance(v, TYPES[t])


def validate(v, s, path):
    if "type" in s:
        ts = s["type"] if isinstance(s["type"], list) else [s["type"]]
        if not any(_type_ok(v, t) for t in ts):
            return fail("%s: expected type %s" % (path, s["type"]))
    if "enum" in s and v not in s["enum"]:
        fail("%s: %r not in enum" % (path, v))
    if "const" in s and v != s["const"]:
        fail("%s: %r != const" % (path, v))
    if isinstance(v, str):
        if "pattern" in s and not re.search(s["pattern"], v):
            fail("%s: %r does not match %s" % (path, v[:40], s["pattern"]))
        if len(v) < s.get("minLength", 0):
            fail("%s: too short" % path)
    if _type_ok(v, "number") and "minimum" in s and v < s["minimum"]:
        fail("%s: below minimum" % path)
    if isinstance(v, list):
        if len(v) < s.get("minItems", 0):
            fail("%s: too few items" % path)
        if "items" in s:
            for i, x in enumerate(v):
                validate(x, s["items"], "%s[%d]" % (path, i))
    if isinstance(v, dict):
        for k in s.get("required", []):
            if k not in v:
                fail("%s: missing %s" % (path, k))
        props = s.get("properties", {})
        for k, x in v.items():
            if k in props:
                validate(x, props[k], path + "." + k)
            elif s.get("additionalProperties") is False:
                fail("%s: unexpected property %s" % (path, k))


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def check_schemas():
    for p in sorted(glob.glob(os.path.join(m.DATA, "*.json")) + glob.glob(os.path.join(m.VECTORS, "*.json"))):
        if p.endswith(".schema.json"):
            s = load(p)
            if s.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
                fail("%s: not a draft 2020-12 schema" % rel(p))
            continue
        sp = p.replace(".json", ".schema.json")
        if not os.path.exists(sp):
            fail("%s: no schema file" % rel(p))
            continue
        doc = load(p)
        if doc.get("$schema") != "./" + os.path.basename(sp):
            fail("%s: $schema must point to %s" % (rel(p), os.path.basename(sp)))
        validate(doc, load(sp), rel(p))


# ---------------------------------------------------------------------------
# 2. Registry consistency
# ---------------------------------------------------------------------------

KEYS = {"limits.json": ("name",), "frame-types.json": ("code",), "tlv-types.json": ("context", "code"),
        "domain-strings.json": ("purpose",), "codes.json": ("registry", "code"), "constants.json": ("name",)}


def entries(name):
    return load(os.path.join(m.DATA, name))["entries"]


def check_registries():
    for name, key in KEYS.items():
        seen = set()
        for e in entries(name):
            k = tuple(e[x] for x in key)
            if k in seen:
                fail("%s: duplicate %s" % (name, k))
            seen.add(k)
    names = set()
    for e in entries("frame-types.json"):
        if m.frame_class(int(e["code"], 16)) != e["class"]:
            fail("frame-types.json: %s outside the %s range" % (e["code"], e["class"]))
        if e["name"] in names:
            fail("frame-types.json: duplicate name %s" % e["name"])
        names.add(e["name"])
    for e in entries("tlv-types.json"):
        if bool(int(e["code"], 16) & 0x80) != e["critical"]:
            fail("tlv-types.json: %s critical flag disagrees with the top bit" % e["code"])
    for e in entries("domain-strings.json"):
        if e["string"] != "MeshLink/1 " + e["purpose"] or len(e["string"]) > 255:
            fail("domain-strings.json: bad string for %s" % e["purpose"])


# ---------------------------------------------------------------------------
# 3. Markdown tables against data files
# ---------------------------------------------------------------------------

MARKER = re.compile(r"<!--\s*data:\s*([\w.-]+)((?:\s+[\w-]+=[\w.-]+)*)\s*-->")


def norm(v):
    if v is None:
        return "-"
    if isinstance(v, bool):
        return "yes" if v else "no"
    s = str(v).strip().strip("`").strip()
    if s in ("—", "–", ""):
        return "-"
    if re.fullmatch(r"\d{1,3}(,\d{3})+", s):
        s = s.replace(",", "")
    if s.lower() == "variable":
        return "-"
    return s


def split_row(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def parse_table(lines, i):
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i + 1 >= len(lines) or not lines[i].lstrip().startswith("|"):
        return None, []
    header = [h.lower().replace(" ", "_") for h in split_row(lines[i])]
    rows = []
    i += 2
    while i < len(lines) and lines[i].lstrip().startswith("|"):
        rows.append(split_row(lines[i]))
        i += 1
    return header, rows


def check_tables():
    covered = {name: set() for name in KEYS}
    covered["vectors"] = set()
    md_files = sorted(glob.glob(os.path.join(m.SPEC, "**", "*.md"), recursive=True))
    for p in md_files:
        lines = open(p, encoding="utf-8").read().split("\n")
        for i, line in enumerate(lines):
            mt = MARKER.search(line)
            if not mt:
                continue
            name = mt.group(1)
            filt = dict(x.split("=") for x in mt.group(2).split())
            header, rows = parse_table(lines, i + 1)
            where = "%s:%d" % (rel(p), i + 1)
            if header is None:
                fail("%s: marker without a table" % where)
                continue
            if name == "vectors":
                check_vector_table(where, header, rows, covered)
                continue
            if name not in KEYS:
                fail("%s: unknown data file %s" % (where, name))
                continue
            data = [e for e in entries(name) if all(str(e.get(k)) == v for k, v in filt.items())]
            key = [k for k in KEYS[name] if k not in filt]
            if len(key) != 1 or header[0] != key[0]:
                fail("%s: first column must be %s" % (where, key))
                continue
            index = {norm(e[key[0]]): e for e in data}
            for r in rows:
                e = index.get(norm(r[0]))
                if e is None:
                    fail("%s: %s not in %s %s" % (where, r[0], name, filt))
                    continue
                covered[name].add(tuple(e[k] for k in KEYS[name]))
                for col, cell in zip(header, r):
                    if col in e and norm(cell) != norm(e[col]):
                        fail("%s: %s %s is %r in Markdown, %r in %s" % (where, r[0], col, cell, e[col], name))
    for name in KEYS:
        for e in entries(name):
            if tuple(e[k] for k in KEYS[name]) not in covered[name]:
                fail("%s: entry %s appears in no marked table" % (name, [e[k] for k in KEYS[name]]))
    for p in vector_files():
        if os.path.basename(p) not in covered["vectors"]:
            fail("%s: not listed in a vectors table" % rel(p))


def vector_files():
    return sorted(p for p in glob.glob(os.path.join(m.VECTORS, "*.json")) if not p.endswith(".schema.json"))


def check_vector_table(where, header, rows, covered):
    if header[:2] != ["file", "tests"]:
        return fail("%s: vectors table needs columns file, tests" % where)
    for r in rows:
        fn = norm(r[0]).strip("[]").split("](")[0].strip("[`")
        p = os.path.join(m.VECTORS, fn)
        if not os.path.exists(p):
            fail("%s: no vector file %s" % (where, fn))
            continue
        covered["vectors"].add(fn)
        if norm(r[1]) != str(load(p)["numberOfTests"]):
            fail("%s: %s has %d tests, table says %s" % (where, fn, load(p)["numberOfTests"], r[1]))


# ---------------------------------------------------------------------------
# 4. Decision log
# ---------------------------------------------------------------------------


def check_decisions():
    log = os.path.join(m.SPEC, "decisions.md")
    text = open(log, encoding="utf-8").read()
    defined = set(re.findall(r"^### (D-\d{2})\b", text, re.M))
    used = set()
    for p in glob.glob(os.path.join(m.SPEC, "*.md")):
        if p != log:
            used |= set(re.findall(r"\[(D-\d{2})\]", open(p, encoding="utf-8").read()))
    for name in KEYS:
        for e in entries(name):
            if e.get("decision", "-") != "-":
                used.add(e["decision"])
    for d in sorted(used - defined):
        fail("decisions.md: %s used but not defined" % d)
    for d in sorted(defined - used):
        fail("decisions.md: %s defined but never used" % d)


# ---------------------------------------------------------------------------
# 5. Vectors
# ---------------------------------------------------------------------------


def b(x):
    return bytes.fromhex(x)


DERIVE = {
    "epoch": lambda i: m.u32(m.epoch_of(i["unix_seconds"])),
    "node-id": lambda i: m.node_id(b(i["ed25519_key"])),
    "mesh-id-digest": lambda i: m.mesh_id_digest(b(i["mesh_id_utf8"]).decode("utf-8"), i["profile"]),
    "mesh-tag-key": lambda i: m.mesh_tag_key(b(i["mesh_id_digest"]), b(i["mesh_key"])),
    "mesh-tag": lambda i: m.mesh_tag(b(i["tag_key"]), i["epoch"]),
    "mesh-psk": lambda i: m.mesh_psk(b(i["mesh_key"]), b(i["mesh_id_digest"])),
    "resolvable-tag": lambda i: m.resolvable_tag(b(i["resolving_key"]), i["epoch"]),
    "public-tag": lambda i: m.public_tag(b(i["x25519_key"]), i["epoch"]),
    "unsecured-tag": lambda i: m.unsecured_tag(b(i["node_id"]), i["epoch"]),
    "conversation-secret": lambda i: m.conversation_secret(b(i["seed"]), b(i["initiator_node_id"]),
                                                           b(i["responder_node_id"])),
    "conversation-tag": lambda i: m.conversation_tag(b(i["conversation_secret"]), i["direction"], i["epoch"]),
    "lookup-tag": lambda i: m.lookup_tag(b(i["salt"]), b(i["node_id"])),
    "tombstone-commitment": lambda i: m.tombstone_commitment(b(i["k"])),
    "lookup-reply-key": lambda i: m.lookup_reply_key(b(i["shared_secret"]), b(i["salt"]), b(i["lookup_tag"]),
                                                     b(i["requester_ephemeral"]), b(i["responder_ephemeral"])),
    "link-prologue": lambda i: m.link_prologue(i["versions"], i["selected"], i["profile"], b(i["mesh_id_digest"])),
    "e2e-prologue": lambda i: m.e2e_prologue(i["version"], i["profile"]),
    "hpke-info": lambda i: m.hpke_info(b(i["sender_static_key"]), b(i["recipient_node_id"])),
    "certificate-signing-input": lambda i: m.certificate_signing_input(b(i["certificate_without_signature"])),
    "broadcast-signing-input": lambda i: m.broadcast_signing_input(b(i["frame_without_signature"])),
}

CODECS = {
    "frame": (m.decode_frame, m.encode_frame),
    "record": (lambda d: (m.decode_record(d), "valid"), m.encode_record),
    "advertisement": (m.decode_advertisement, m.encode_advertisement),
    "capability": (lambda d: (m.decode_capability(d), "valid"), m.encode_capability),
    "certificate": (lambda d: (m.decode_certificate(d), "valid"), m.encode_certificate),
    "handshake-payload": (lambda d: (m.decode_handshake_payload(d), "valid"), m.encode_handshake_payload),
}


def codec(group):
    lay = group["layout"]
    if lay.startswith("inner-"):
        content, sec = lay[6:], group["secured"]
        return (lambda d: (m.decode_inner(d, content, sec), "valid"),
                lambda f: m.encode_inner(f, content, sec))
    return CODECS[lay]


def check_vectors():
    total = 0
    for p in vector_files():
        doc = load(p)
        n = 0
        ids = []
        for g in doc["testGroups"]:
            for t in g["tests"]:
                n += 1
                ids.append(t["tcId"])
                where = "%s tcId %d" % (os.path.basename(p), t["tcId"])
                if "derivation" in g:
                    fn = DERIVE.get(g["derivation"])
                    if fn is None:
                        fail("%s: unknown derivation" % where)
                    elif fn(t["inputs"]).hex() != t["output"]:
                        fail("%s: derivation mismatch" % where)
                    if "domain" in g and g["domain"] not in m.DOMAINS.values():
                        fail("%s: unregistered domain string" % where)
                    continue
                dec, enc = codec(g)
                data = b(t["encoded"])
                try:
                    fields, result = dec(data)
                    err = None
                except m.DecodeError as e:
                    fields, result, err = None, "invalid", e.code
                if result != t["result"]:
                    fail("%s: result %s, expected %s (%s)" % (where, result, t["result"], err))
                elif result == "invalid":
                    if err != t.get("error"):
                        fail("%s: error %s, expected %s" % (where, err, t.get("error")))
                else:
                    if fields != t.get("decoded"):
                        fail("%s: decoded fields differ" % where)
                    if enc(fields) != data:
                        fail("%s: re-encoding differs" % where)
        if n != doc["numberOfTests"]:
            fail("%s: numberOfTests %d, found %d" % (rel(p), doc["numberOfTests"], n))
        if ids != list(range(1, n + 1)):
            fail("%s: tcIds must be 1..n in order" % rel(p))
        total += n
    return total


def main():
    check_schemas()
    check_registries()
    check_tables()
    check_decisions()
    total = check_vectors()
    for e in ERRORS:
        print("FAIL " + e)
    if ERRORS:
        print("%d problem(s)" % len(ERRORS))
        return 1
    print("spec OK: %d vectors recomputed" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
