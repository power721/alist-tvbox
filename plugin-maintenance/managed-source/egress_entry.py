"""Observe kernel controls and a fixed broker bundle before accepting source bytes."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path("/opt/at-maintenance")


def main():
    spec = importlib.util.spec_from_file_location("managed_fixed_entry", ROOT / "managed/entry.py")
    original = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(original)
    header = original.header(sys.stdin.buffer.readline(2049))
    manifest = (ROOT / "runtime/manifest.json").read_bytes()
    original.require(hashlib.sha256(manifest).hexdigest() == header["runtimeSha256"])
    for name, record in json.loads(manifest)["files"].items():
        original.require(name in original_manifest_files())
        body = (ROOT / "runtime" / name).read_bytes()
        original.require(len(body) == record["bytes"] and hashlib.sha256(body).hexdigest() == record["sha256"])
    spec = importlib.util.spec_from_file_location("egress_fixed_controls", ROOT / "container_entry.py")
    controls = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(controls)
    ready = {"schemaVersion": 1, "phase": "ready_before_source", "jobId": header["jobId"],
             "runtimeSha256": header["runtimeSha256"], "controls": controls.observe_controls(),
             "files": {name: hashlib.sha256((ROOT / "managed" / name).read_bytes()).hexdigest()
                       for name in ("egress_entry.py", "egress_payload.py", "egress_bridge.py")}}
    print(json.dumps(ready, separators=(",", ":")), flush=True)
    ack = sys.stdin.buffer.readline(2049)
    original.require(len(ack) <= 2048 and ack.endswith(b"\n"))
    original.require(json.loads(ack, object_pairs_hook=original.unique)
                     == {"operation": "execute", "jobId": header["jobId"]})
    body = sys.stdin.buffer.read(header["sourceBytes"])
    original.require(len(body) == header["sourceBytes"]
                     and hashlib.sha256(body).hexdigest() == header["sourceSha256"])
    original.require(sys.stdin.buffer.read(1) == b"\n")
    with Path("/work/managed-source.py").open("xb") as stream:
        stream.write(body)
    # BufferedReader may prefetch broker input; no further bytes are sent before exec.
    os.execve(sys.executable, [sys.executable, "-I", str(ROOT / "managed/egress_payload.py")],
              {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": "/work", "LANG": "C.UTF-8",
               "TMPDIR": "/work", "PYTHONDONTWRITEBYTECODE": "1"})


def original_manifest_files():
    return {"Atvp.py", "bootstrap.py", "demo.py", "probe_fixture.py", "t4cenc.py", "t4worker.py"}


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        raise SystemExit(1) from None
