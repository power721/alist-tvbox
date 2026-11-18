"""Freeze canonical-LF AT owners plus the trusted fixture and launcher."""
import argparse
import hashlib
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
ROOT = MODULE.parent
TARGET = MODULE / "server/src/main/resources/worker/runtime"
BASELINE = "7532bb69a1d9399d8b08520da0d794b31aad1a27"
OWNERS = {
    "t4worker.py": ("src/main/resources/scripts/t4worker.py", "b2d96e41a7605d5c6d7322165426b6a49b435dc156a786fea927c0b63d5cf873"),
    "Atvp.py": ("src/main/resources/static/Atvp.py", "6a7291b511f3424f6a2d824d20d45a8144c444bdd74a66b13a197a2db0f0e73c"),
    "t4cenc.py": ("src/main/resources/scripts/t4cenc.py", "48d1ddbdb5a825f1afcb1592cb7b937ac97b6811da95e0188c51ef97dd22193a"),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files = {}
    for name, (source, expected) in OWNERS.items():
        body = (ROOT / source).read_bytes().replace(b"\r\n", b"\n")
        assert hashlib.sha256(body).hexdigest() == expected, f"AT baseline drift: {source}"
        files[name] = body
    files["demo.py"] = (MODULE / "fixtures/demo.py").read_bytes().replace(b"\r\n", b"\n")
    for name in ("bootstrap.py", "probe_fixture.py"):
        files[name] = (TARGET / name).read_bytes().replace(b"\r\n", b"\n")
    records = {name: {"sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}
               for name, body in sorted(files.items())}
    manifest = {"schemaVersion": 1, "baselineCommit": BASELINE, "mode": "offline_fixture",
                "fixtureSha256": records["demo.py"]["sha256"], "files": records,
                "authority": "expected_bundle", "boundary": "Not a deployed runtime or sandbox receipt"}
    files["manifest.json"] = json.dumps(manifest, separators=(",", ":")).encode()
    for name, body in files.items():
        path = TARGET / name
        if args.check:
            assert path.read_bytes() == body, f"Runtime bundle stale: {name}"
        else:
            path.write_bytes(body)
    print(json.dumps({"files": len(records), "runtimeSha256": hashlib.sha256(files["manifest.json"]).hexdigest()}))


if __name__ == "__main__":
    main()
