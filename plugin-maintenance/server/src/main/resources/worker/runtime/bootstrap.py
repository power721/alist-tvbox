"""Observe the trusted fixture loader; network hooks are NOT a security sandbox."""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import runpy
import socket
import sys

import requests

root = Path(__file__).resolve().parent
manifest_bytes = (root / "manifest.json").read_bytes()
manifest = json.loads(manifest_bytes)
for name, record in manifest["files"].items():
    body = (root / name).read_bytes()
    if len(body) != record["bytes"] or hashlib.sha256(body).hexdigest() != record["sha256"]:
        raise RuntimeError("RUNTIME_IDENTITY_MISMATCH")


def blocked(*args, **kwargs):
    raise RuntimeError("NETWORK_DISABLED_IN_FIXTURE")


socket.socket.connect = blocked
socket.create_connection = blocked
socket.getaddrinfo = blocked
requests.sessions.Session.request = blocked
sys.argv = [str(root / "t4worker.py"), str(root / "Atvp.py")]
namespace = runpy.run_path(sys.argv[0], run_name="maintenance_fixture")
# runpy's returned mapping is not necessarily the function globals mapping.
worker = namespace["main"].__globals__
load_atvp = worker["_load_atvp"]
receipt = {}


def observed_load():
    load_atvp()
    cls = type(worker["_spider"])
    load_inner = cls._load_inner_spider_class

    def observed_inner(instance, source_text):
        cls_result = load_inner(instance, source_text)
        receipt["loadedTextSha256"] = hashlib.sha256(source_text.encode("utf-8")).hexdigest()
        return cls_result

    cls._load_inner_spider_class = observed_inner


worker["_load_atvp"] = observed_load
handle = worker["handle"]


def observed_handle(method, params):
    if method == "init":
        outer = json.loads(__import__("base64").b64decode(params["extend"]))
        if outer != {"source": str(root / "demo.py"), "raw": True}:
            raise RuntimeError("FIXTURE_CONFIG_MISMATCH")
        source_sha = hashlib.sha256((root / "demo.py").read_bytes()).hexdigest()
        if source_sha != manifest["fixtureSha256"]:
            raise RuntimeError("FIXTURE_IDENTITY_MISMATCH")
        result = handle(method, params)
        result["maintenanceReceipt"] = {
            **receipt, "sourceBytesSha256": source_sha,
            "runtimeSha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "runtimeFiles": manifest["files"], "authority": "bootstrap_observed",
            "python": platform.python_version(),
            "dependencies": {name: importlib.metadata.version(name)
                             for name in ("requests", "pycryptodome", "lxml")},
        }
        return result
    return handle(method, params)


worker["handle"] = observed_handle
worker["main"]()
