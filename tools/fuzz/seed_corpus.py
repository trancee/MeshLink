#!/usr/bin/env python3
"""Writes the Jazzer seed inputs for each :meshlink-core:fuzz target from the vector files.

Each target gets every `encoded` value of its vector file, valid and invalid, one file per input
named by its SHA-1. `ResolvingKey` gets the bodies of the RESOLVING_KEY_UPDATE link frames.
The seeds are not committed: `:meshlink-core:fuzz -Pfuzz.seeds=DIR` merges them into the
committed corpus and keeps only the inputs that add coverage.

Usage: python3 tools/fuzz/seed_corpus.py OUT_DIR   (standard library only)
"""
import hashlib, json, pathlib, sys

VECTORS = pathlib.Path(__file__).resolve().parents[2] / "docs/spec/vectors"
RESOLVING_KEY_UPDATE = 0x0E


def encoded(name, layout=None):
    document = json.loads((VECTORS / name).read_text(encoding="utf-8"))
    return [
        bytes.fromhex(test["encoded"])
        for group in document["testGroups"]
        if layout is None or group["layout"] == layout
        for test in group["tests"]
    ]


TARGETS = {
    "Advertisement": lambda: encoded("advertisement.json"),
    "CapabilityRecord": lambda: encoded("capability.json"),
    "LinkRecord": lambda: encoded("records.json"),
    "LinkFrame": lambda: encoded("link-frames.json"),
    "HandshakePayload": lambda: encoded("handshake-payload.json"),
    "IdentityCertificate": lambda: encoded("certificate.json"),
    "MessageFrame": lambda: encoded("routed-frames.json"),
    "UnicastFrame": lambda: encoded("routed-frames.json"),
    "RouteRequest": lambda: encoded("routed-frames.json"),
    "RouteReply": lambda: encoded("routed-frames.json"),
    "BroadcastFrame": lambda: encoded("routed-frames.json"),
    "FloodFrame": lambda: encoded("routed-frames.json"),
    "MessageContent": lambda: encoded("inner-plaintext.json", "inner-message"),
    "ReceiptContent": lambda: encoded("inner-plaintext.json", "inner-receipt"),
    "ResolvingKey": lambda: [
        frame[1:] for frame in encoded("link-frames.json") if frame[:1] == bytes([RESOLVING_KEY_UPDATE])
    ],
}


def main(out):
    for target, seeds in TARGETS.items():
        directory = out / target
        directory.mkdir(parents=True, exist_ok=True)
        for seed in seeds():
            (directory / hashlib.sha1(seed).hexdigest()).write_bytes(seed)
        print(f"{target}: {len(list(directory.iterdir()))} seeds")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))
