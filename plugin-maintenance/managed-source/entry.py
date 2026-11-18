"""Pre-execution control handshake. No submitted code runs before the host ACK."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path("/opt/at-maintenance")
LIMIT = 1048576


def require(condition):
    if not condition:
        raise ValueError("MANAGED_ENTRY_INVALID")


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def header(body):
    require(len(body) <= 2048 and body.endswith(b"\n"))
    value = json.loads(body, object_pairs_hook=unique)
    require(isinstance(value, dict) and set(value) == {
        "schemaVersion", "jobId", "sourceSha256", "sourceBytes", "runtimeSha256"})
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1)
    require(isinstance(value["jobId"], str) and re.fullmatch(
        r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", value["jobId"]))
    require(type(value["sourceBytes"]) is int and 0 < value["sourceBytes"] <= LIMIT)
    require(all(isinstance(value[key], str) and re.fullmatch(r"[a-f0-9]{64}", value[key])
                for key in ("sourceSha256", "runtimeSha256")))
    return value


def main():
    value = header(sys.stdin.buffer.readline(2049))
    manifest = (ROOT / "runtime/manifest.json").read_bytes()
    require(hashlib.sha256(manifest).hexdigest() == value["runtimeSha256"])
    for name, record in json.loads(manifest)["files"].items():
        require(re.fullmatch(r"[A-Za-z0-9_]+\.py", name))
        body = (ROOT / "runtime" / name).read_bytes()
        require(len(body) == record["bytes"] and hashlib.sha256(body).hexdigest() == record["sha256"])
    spec = importlib.util.spec_from_file_location("fixed_control_observer", ROOT / "container_entry.py")
    observer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(observer)
    ready = {"schemaVersion": 1, "phase": "ready_before_source", "jobId": value["jobId"],
             "entrySha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
             "payloadSha256": hashlib.sha256((ROOT / "managed/payload.py").read_bytes()).hexdigest(),
             "runtimeSha256": value["runtimeSha256"], "controls": observer.observe_controls()}
    print(json.dumps(ready, separators=(",", ":")), flush=True)
    ack = sys.stdin.buffer.readline(2049)
    require(len(ack) <= 2048 and ack.endswith(b"\n"))
    require(json.loads(ack, object_pairs_hook=unique) == {"operation": "execute", "jobId": value["jobId"]})
    body = sys.stdin.buffer.read(value["sourceBytes"] + 1)
    require(len(body) == value["sourceBytes"] and hashlib.sha256(body).hexdigest() == value["sourceSha256"])
    target = Path("/work/managed-source.py")
    with target.open("xb") as stream:
        stream.write(body)
    target.chmod(0o600)
    # From this point all process output, including identity claims, is untrusted.
    os.execve(sys.executable, [sys.executable, "-I", str(ROOT / "managed/payload.py")],
              {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": "/work", "LANG": "C.UTF-8",
               "TMPDIR": "/work", "PYTHONDONTWRITEBYTECODE": "1"})


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError):
        raise SystemExit(1) from None
