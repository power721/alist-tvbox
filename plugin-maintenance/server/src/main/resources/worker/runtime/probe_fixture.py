"""Exercise the pinned trusted worker JSONL protocol; Java enforces the total timeout."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(__file__).resolve().parent
manifest_bytes = (root / "manifest.json").read_bytes()
manifest = json.loads(manifest_bytes)
runtime_sha = hashlib.sha256(manifest_bytes).hexdigest()
if sys.argv[1:] != [manifest["fixtureSha256"], runtime_sha]:
    raise RuntimeError("FROZEN_IDENTITY_MISMATCH")
for name, record in manifest["files"].items():
    body = (root / name).read_bytes()
    if hashlib.sha256(body).hexdigest() != record["sha256"] or len(body) != record["bytes"]:
        raise RuntimeError("RUNTIME_IDENTITY_MISMATCH")

steps = []
passed = []
with (root / "worker.log").open("wb") as log:
    process = subprocess.Popen([sys.executable, "-I", str(root / "bootstrap.py")],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                               env=dict(os.environ, ATVP_CACHE_FILE=str(root / "cache.json")))

    def call(method, params, derived_from):
        ident = str(len(steps) + 1)
        start = time.monotonic()
        process.stdin.write((json.dumps({"id": ident, "method": method, "params": params}) + "\n").encode())
        process.stdin.flush()
        line = process.stdout.readline(262_145)
        if not line or len(line) > 262_144:
            raise RuntimeError("WORKER_OUTPUT_INVALID")
        response = json.loads(line)
        if response["id"] != ident or response["ok"] is not True:
            raise RuntimeError("WORKER_PROTOCOL_FAILED")
        steps.append({"requestId": ident, "method": method, "inputFrom": derived_from,
                      "input": {"fixtureConfig": "bundled_empty_inner_ext"} if method == "init" else params,
                      "elapsedMs": round((time.monotonic() - start) * 1000, 3),
                      "responseSha256": hashlib.sha256(line).hexdigest()})
        return response["result"]

    try:
        outer = base64.b64encode(json.dumps({"source": str(root / "demo.py"), "raw": True}).encode()).decode()
        initialized = call("init", {"extend": outer}, "bundled_fixture_empty_inner_ext")
        receipt = initialized["maintenanceReceipt"]
        assert receipt["sourceBytesSha256"] == receipt["loadedTextSha256"] == manifest["fixtureSha256"]
        assert receipt["runtimeSha256"] == runtime_sha
        assert receipt["authority"] == "bootstrap_observed"
        passed.append("S06")
        home = call("home", {"filter": True}, "init")
        classes = home["class"]
        assert classes and all(isinstance(c["type_id"], str) and c["type_id"] and c["type_name"] for c in classes)
        assert isinstance(home.get("filters", {}), dict)
        category = call("category", {"t": classes[0]["type_id"], "pg": "2", "filter": True, "extend": {}},
                        "home.class[0].type_id")
        assert category["page"] == 2 and category["list"]
        passed.append("C01")
        vod_id = category["list"][0]["vod_id"]
        detail = call("detail", {"ids": vod_id}, "category.list[0].vod_id")["list"]
        assert len(detail) == 1 and detail[0]["vod_id"] == vod_id
        card = detail[0]
        assert card["vod_name"] and isinstance(card["vod_pic"], str) and card["vod_content"]
        invalid = call("detail", {"ids": "maintenance-missing-fixture-id"}, "negative_case_invalid_id")
        assert invalid["list"] == []
        passed.append("C07")
        flags = card["vod_play_from"].split("$$$")
        groups = card["vod_play_url"].split("$$$")
        assert len(flags) == len(groups) and all(flags)
        episodes = 0
        for flag, group in zip(flags, groups):
            for item in group.split("#"):
                label, play_id = item.split("$", 1)
                assert label and play_id
                player = call("play", {"flag": flag, "play": play_id}, "detail.vod_play_from/vod_play_url")
                assert player["parse"] == 0 and player["jx"] == 0 and player["playUrl"] == ""
                assert player["url"] == "https://example.invalid/fixture.mp4" and player["header"] == {}
                episodes += 1
        passed.extend(["C10", "R01"])
        search = call("search", {"wd": "fixture", "pg": "2", "quick": False}, "fixed_search_case_page_2")
        assert search["list"][0]["vod_name"] == "fixture:2"
        assert call("destroy", {}, "worker_lifecycle") == {"status": "closed"}
        process.stdin.close()
        assert process.wait(timeout=3) == 0
        result = {"evidenceClass": "offline_fixture", "receipt": receipt, "passedNodes": passed,
                  "steps": steps, "coverage": {"categories": 1, "categoryPages": [2],
                                               "detailValid": 1, "detailInvalid": 1,
                                               "lines": len(flags), "episodes": episodes,
                                               "searchPages": [2]},
                  "boundary": "Trusted fixture through worker JSONL; not T4 HTTP, media, direct FongMi or client proof"}
    finally:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
        # On exceptional paths keep this parent alive until Java terminates the
        # whole tree. Do not orphan the Windows interpreter behind its launcher.
        if process.poll() is None:
            process.wait()
        process.stdout.close()
print(json.dumps(result))
