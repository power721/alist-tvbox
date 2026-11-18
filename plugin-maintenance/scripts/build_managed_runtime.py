"""Build/check the data-only identity of the separately installed trusted executor."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "server/src/main/resources/worker/managed-runtime.json"
FILES = (
    "scripts/build_managed_runtime.py",
    "scripts/managed_source_broker.py",
    "scripts/managed_http_acceptance.py",
    "scripts/managed_source_protocol.py",
    "server/src/main/resources/worker/container_supervisor.py",
    "server/src/main/resources/worker/container_entry.py",
    "managed-source/entry.py",
    "managed-source/payload.py",
    "scripts/managed_egress_protocol.py",
    "scripts/managed_loader.py",
    "scripts/source_egress.py",
    "managed-source/egress_entry.py",
    "managed-source/egress_payload.py",
    "managed-source/egress_bridge.py",
)


def document():
    import managed_source_protocol as M
    import managed_egress_protocol as G
    import managed_http_acceptance as HTTP
    records = {}
    for name in FILES:
        body = (ROOT / name).read_bytes()
        records[name] = {"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    runtime = (ROOT / "server/src/main/resources/worker/runtime/manifest.json").read_bytes()
    return {"schemaVersion": 1, "protocol": "managed-source-unix-v1",
            "runtimeSha256": hashlib.sha256(runtime).hexdigest(), "files": records,
            "entrySha256": records["managed-source/entry.py"]["sha256"],
            "payloadSha256": records["managed-source/payload.py"]["sha256"],
            "egressFiles": {name: records["managed-source/" + name]["sha256"]
                            for name in ("egress_entry.py", "egress_payload.py", "egress_bridge.py")},
            "policy": M.POLICY, "egressPolicy": G.POLICY, "httpPolicy": HTTP.POLICY,
            "boundary": "Trusted host broker identity; not an AT/T4 or business receipt."}


def body():
    return json.dumps(document(), ensure_ascii=True, separators=(",", ":")).encode("utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    expected = body()
    if args.write:
        OUTPUT.write_bytes(expected)
    elif not OUTPUT.is_file() or OUTPUT.read_bytes() != expected:
        raise SystemExit("MANAGED_RUNTIME_BUNDLE_CHANGED")
    print(json.dumps({"bundleSha256": hashlib.sha256(expected).hexdigest(),
                      "files": len(FILES), "matched": True}))
