"""Verify the checked-out ATVP/T4 worker using only the bundled offline fixture."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading

MODULE = Path(__file__).resolve().parents[1]
REPO = MODULE.parent
BASELINE_COMMIT = "7532bb69a1d9399d8b08520da0d794b31aad1a27"
# Verified against `git show <BASELINE_COMMIT>:<path>`; canonical LF bytes let
# both Windows checkouts and the offline source archive run the same fixture.
CANONICAL_PINS = {
    "worker": "b2d96e41a7605d5c6d7322165426b6a49b435dc156a786fea927c0b63d5cf873",
    "atvp": "6a7291b511f3424f6a2d824d20d45a8144c444bdd74a66b13a197a2db0f0e73c",
    "cenc": "48d1ddbdb5a825f1afcb1592cb7b937ac97b6811da95e0188c51ef97dd22193a",
}


def main():
    runtime = {
        "worker": REPO / "src/main/resources/scripts/t4worker.py",
        "atvp": REPO / "src/main/resources/static/Atvp.py",
        "cenc": REPO / "src/main/resources/scripts/t4cenc.py",
    }
    hashes = {}
    canonical_hashes = {}
    for name, path in runtime.items():
        body = path.read_bytes()
        canonical = body.replace(b"\r\n", b"\n")
        hashes[name] = hashlib.sha256(body).hexdigest()
        canonical_hashes[name] = hashlib.sha256(canonical).hexdigest()
        assert canonical_hashes[name] == CANONICAL_PINS[name], f"{name}: source drift"
    fixture = MODULE / "fixtures/demo.py"
    with tempfile.TemporaryDirectory(prefix="at-offline-fixture-") as tmp:
        responses = queue.Queue()
        log = (Path(tmp) / "worker.log").open("wb")
        process = subprocess.Popen(
            [sys.executable, "-I", str(MODULE / "scripts/fixture_runtime_runner.py"),
             str(runtime["worker"]), str(runtime["atvp"])],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
            env=dict(os.environ, ATVP_CACHE_FILE=str(Path(tmp) / "cache.json")))

        def read():
            for line in process.stdout:
                responses.put(json.loads(line.decode("utf-8")))
            responses.put(None)

        threading.Thread(target=read, daemon=True).start()

        def call(method, params):
            ident = str(call.counter)
            call.counter += 1
            process.stdin.write((json.dumps({"id": ident, "method": method, "params": params}) + "\n").encode())
            process.stdin.flush()
            reply = responses.get(timeout=15)
            assert reply is not None, "Worker exited"
            assert reply["id"] == ident
            assert reply["ok"], reply.get("error")
            return reply["result"]

        call.counter = 0
        try:
            outer = base64.b64encode(json.dumps({"source": str(fixture), "raw": True}).encode()).decode()
            call("init", {"extend": outer})
            home = call("home", {})
            category = call("category", {"t": home["class"][0]["type_id"], "pg": "2", "extend": {}})
            assert category["page"] == 2
            vod_id = category["list"][0]["vod_id"]
            detail = call("detail", {"ids": vod_id})["list"][0]
            assert detail["vod_id"] == vod_id
            flag = detail["vod_play_from"]
            play_id = detail["vod_play_url"].split("$", 1)[1]
            player = call("play", {"flag": flag, "play": play_id})
            assert player["url"] == "https://example.invalid/fixture.mp4"
            search = call("search", {"wd": "fixture", "pg": "2", "quick": False})
            assert search["list"][0]["vod_name"] == "fixture:2"
            call("destroy", {})
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            log.close()
    print(json.dumps({
        "evidenceClass": "offline_fixture", "runtimeHashes": hashes,
        "baselineCommit": BASELINE_COMMIT, "canonicalLfHashes": canonical_hashes,
        "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
        "passed": ["init", "home_to_category", "category_page_2", "category_to_detail",
                   "detail_to_player_with_flag", "search_page_2", "destroy"],
        "boundary": "Direct worker fixture only; not T4 HTTP, media, client or live-source proof",
    }, indent=2))


if __name__ == "__main__":
    main()
