"""Freeze the bounded NAS inventory increment without exporting secrets or source."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT.parent
EVIDENCE = BASE / "recovery/nas-directory-20261109"
sys.path.insert(0, str(BASE / "recovery"))
from check_nas_directory_ui import api, SOURCE_SHA


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(body):
    return hashlib.sha256(body).hexdigest()


def main():
    output = EVIDENCE / "nas-directory-029-final-verification.json"
    require(not output.exists(), "Final receipt already exists")
    old_freeze = json.loads((BASE / "recovery/ui-directory-20261109/directory-028-final-verification.json").read_bytes())
    snapshot = BASE / "recovery/nas-directory-before-20261109"
    for record in old_freeze["frozenFiles"]:
        require(sha((snapshot / record["path"]).read_bytes()) == record["sha256"], "Old frozen bytes missing")
    old_jar = ROOT / "plugin-maintenance/server/target/at-plugin-maintenance-0.28.0-SNAPSHOT.jar"
    require(sha(old_jar.read_bytes()) == old_freeze["jarSha256"], "Old 0.28 jar changed")
    old27 = ROOT / "plugin-maintenance/server/target/at-plugin-maintenance-0.27.0-SNAPSHOT.jar"
    require(sha(old27.read_bytes()) == "83fbafe1a4c3797641dd24b1d7374d5f043e63c98ebe43738f87a1c117c95eb0",
            "Old 0.27 jar changed")
    jar = ROOT / "plugin-maintenance/server/target/at-plugin-maintenance-0.29.0-SNAPSHOT.jar"
    with zipfile.ZipFile(jar) as archive:
        for path in (ROOT / "plugin-maintenance/web-ui/dist").rglob("*"):
            if path.is_file():
                relative = path.relative_to(ROOT / "plugin-maintenance/web-ui/dist").as_posix()
                require(archive.read("BOOT-INF/classes/static/" + relative) == path.read_bytes(), "UI jar mismatch")
    build = (EVIDENCE / "backend-029-package.log").read_text(encoding="utf-8")
    require("Tests run: 323, Failures: 0, Errors: 0, Skipped: 0" in build and "BUILD SUCCESS" in build,
            "Backend package evidence missing")
    suites = [ET.parse(path).getroot() for path in
              (ROOT / "plugin-maintenance/server/target/surefire-reports").glob("TEST-*.xml")]
    require(sum(int(suite.get("tests", "0")) for suite in suites) == 323
            and all(int(suite.get(key, "0")) == 0 for suite in suites for key in ("failures", "errors", "skipped")),
            "Backend XML results invalid")
    unit = (EVIDENCE / "frontend-unit-final.log").read_text(encoding="utf-8")
    require("tests 26" in unit and "pass 26" in unit and "fail 0" in unit, "Frontend evidence invalid")
    owner = (EVIDENCE / "owner-unit-final.log").read_text(encoding="utf-8")
    require("Ran 5 tests" in owner and "OK" in owner, "Owner checks missing")
    posix = json.loads((EVIDENCE / "scanner-tests-01.json").read_bytes())
    require(posix["passed"] == 8 and posix["failed"] == 0 and posix["skipped"] == 0
            and posix["scannerSha256"] == sha((ROOT / "plugin-maintenance/scripts/nas_directory_scan.py").read_bytes())
            and posix["testsSha256"] == sha((ROOT / "plugin-maintenance/scripts/test_nas_directory_scan.py").read_bytes()),
            "POSIX check identity mismatch")
    inventories = {}
    for name, count in (("at-static-directory-final.json", 311), ("t4-directory-final.json", 0)):
        value = json.loads((EVIDENCE / name).read_bytes())
        require(value["productionIdentityUnchanged"] and len(value["files"]) == count
                and value["scannerSha256"] == posix["scannerSha256"] and value["scan"]["complete"]
                and all(value[key] is False for key in ("sourceExecuted", "contentsStored", "modelCalled", "published")),
                "Inventory boundary invalid")
        inventories[value["owner"]["target"]] = {
            "files": count, "hashed": sum(row["status"] == "hashed" for row in value["files"]),
            "sourceBytesRead": value["scan"]["sourceBytesRead"], "fileSha256": sha((EVIDENCE / name).read_bytes())}
    require(inventories["at_static"]["hashed"] == 310 and inventories["t4_custom"]["hashed"] == 0,
            "Real inventory counts changed")
    browsers = []
    for suffix, count in (("desktop", 7), ("mobile", 7), ("regression-desktop", 5), ("regression-mobile", 5)):
        value = json.loads((EVIDENCE / ("browser-029-" + suffix + ".json")).read_bytes())
        require(value["passed"] == count and len(value["checks"]) == count and value["productionCalled"] is False
                and value["realModelCalled"] is False, "Browser checks missing")
        browsers.append(value)
    get = api(8799, EVIDENCE / "backend-029.out.log")
    require(not get("/at/connection")["configured"] and not get("/model/connection")["enabled"]
            and not get("/scheduling")["enabled"], "Owned local instance boundary changed")
    require(get("/container-runtime")["blockedReason"] == "CONTAINER_EXECUTION_DISABLED"
            and get("/media-policy")["blockedReason"] == "MEDIA_EXECUTION_DISABLED",
            "Owned local execution unexpectedly enabled")
    source = next(row for row in get("/sources") if row["artifactSha256"] == SOURCE_SHA)
    body = get(f"/sources/{source['id']}/artifacts/{SOURCE_SHA}", raw=True)
    require(len(body) == 36902 and sha(body) == SOURCE_SHA, "Local source identity changed")
    runs = get(f"/sources/{source['id']}/probe-runs")
    require(len(runs) == 1 and runs[0]["executionMode"] == "static"
            and runs[0]["status"] == "COMPLETED", "Local source execution boundary changed")
    states = [node["status"] for node in runs[0]["result"]["nodes"]]
    require(states.count("pass") == 3 and states.count("not_tested") == 41, "Static scope changed")
    knowledge = get("/knowledge?limit=12")
    require(len(knowledge["rules"]) == 12 and len(get("/capability-nodes")["nodes"]) == 44,
            "Knowledge identity changed")
    files = [
        ROOT / "plugin-maintenance/server/pom.xml", jar,
        ROOT / "docs/plugin-maintenance/nas-directory-progress-2026-11-09.md",
        ROOT / ".claude/memory/handoff.md", ROOT / ".claude/memory/MEMORY.md",
        ROOT / ".claude/memory/conversations/2026-11-09.md",
        ROOT / "plugin-maintenance/scripts/nas_directory_scan.py",
        ROOT / "plugin-maintenance/scripts/test_nas_directory_scan.py",
        Path(__file__), BASE / "recovery/nas_directory_inventory.py",
        BASE / "recovery/test_nas_directory_inventory.py", BASE / "recovery/check_nas_directory_ui.py"]
    ui = ROOT / "plugin-maintenance/web-ui"
    files += [ui / name for name in ("tsconfig.json", "src/App.vue", "src/NasDirectoryPanel.vue",
                                    "src/nasInventory.ts", "tests/nasInventory.test.mjs", "tests/browser-nas-check.js")]
    files += [path for path in EVIDENCE.iterdir()
              if path.suffix in {".png", ".json", ".log"}
              and path.name not in {"backend-029.out.log", "backend-029.err.log"}]
    frozen = [{"path": str(path.relative_to(BASE)), "sha256": sha(path.read_bytes()), "bytes": path.stat().st_size}
              for path in sorted(set(files))]
    result = {"schemaVersion": 1, "version": "0.29.0-SNAPSHOT", "jarSha256": sha(jar.read_bytes()),
              "backendTests": 323, "frontendTests": 26, "ownerTests": 5, "posixTests": 8,
              "browserChecks": browsers, "inventories": inventories, "oldFrozenFilesPreserved": 30,
              "knowledgeRules": 12, "capabilityNodes": 44, "frozenFiles": frozen,
              "nasProductionReadOnlyCalled": True, "productionWriteRequests": 0,
              "sourceExecuted": False, "realModelCalled": False, "published": False,
              "managedExecutorProductConnected": False, "t4BusinessVerified": False,
              "boundary": "Unsigned local inventory preview; hashes are not runtime or source-health proof."}
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"receipt": str(output), "frozenFiles": len(frozen), "inventories": inventories}))


if __name__ == "__main__":
    main()
