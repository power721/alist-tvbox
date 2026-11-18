"""Freeze local-only directory UI evidence without exporting login or source contents."""
from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT.parent / "recovery/ui-directory-20261109"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(body):
    return hashlib.sha256(body).hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        raise RuntimeError("Local verification must not follow redirects")


def main():
    output = EVIDENCE / "directory-028-final-verification.json"
    require(not output.exists(), "Final receipt already exists")
    jar = ROOT / "plugin-maintenance/server/target/at-plugin-maintenance-0.28.0-SNAPSHOT.jar"
    old_jar = ROOT / "plugin-maintenance/server/target/at-plugin-maintenance-0.27.0-SNAPSHOT.jar"
    require(sha(old_jar.read_bytes()) == "83fbafe1a4c3797641dd24b1d7374d5f043e63c98ebe43738f87a1c117c95eb0",
            "Historical 0.27 artifact changed")
    dist = ROOT / "plugin-maintenance/web-ui/dist"
    packaged = []
    with zipfile.ZipFile(jar) as archive:
        for path in sorted(dist.rglob("*")):
            if path.is_file():
                relative = path.relative_to(dist).as_posix()
                require(archive.read("BOOT-INF/classes/static/" + relative) == path.read_bytes(),
                        "Packaged UI differs from current build")
                packaged.append(relative)

    build = (EVIDENCE / "backend-028-package.log").read_text(encoding="utf-8", errors="strict")
    require("Tests run: 323, Failures: 0, Errors: 0, Skipped: 0" in build and "BUILD SUCCESS" in build,
            "Full backend package check missing")
    suites = [ET.parse(path).getroot() for path in
              (ROOT / "plugin-maintenance/server/target/surefire-reports").glob("TEST-*.xml")]
    require(sum(int(suite.get("tests", "0")) for suite in suites) == 323
            and all(int(suite.get(field, "0")) == 0 for suite in suites
                    for field in ("failures", "errors", "skipped")), "Backend XML results changed")
    unit = (EVIDENCE / "frontend-unit-final.log").read_text(encoding="utf-8")
    require("tests 15" in unit and "pass 15" in unit and "fail 0" in unit, "Frontend unit checks missing")
    browsers = []
    for name, viewport in (("browser-028-desktop-recheck.json", {"width": 1440, "height": 1000}),
                           ("browser-028-mobile-check.json", {"width": 390, "height": 844})):
        result = json.loads((EVIDENCE / name).read_bytes())
        require(result["passed"] == 5 and len(result["checks"]) == 5 and result["viewport"] == viewport,
                "Packaged browser checks missing")
        require(all(result[key] is False for key in
                    ("productionCalled", "realModelCalled", "submittedSourceExecuted")), "Unexpected evidence scope")
        browsers.append(result)

    log = (EVIDENCE / "backend-028.out.log").read_text(encoding="utf-8")
    match = re.search(r"Using generated security password: ([^\s]+)", log)
    require(match is not None, "Owned local instance login unavailable")
    credential = "Basic " + base64.b64encode(("admin:" + match.group(1)).encode()).decode()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def get(path, raw=False):
        request = urllib.request.Request("http://127.0.0.1:8798/api/plugin-maintenance" + path,
                                         headers={"Authorization": credential})
        with opener.open(request, timeout=15) as response:
            require(response.status == 200, "Local verification request failed")
            payload = response.read(2_097_153)
            require(len(payload) <= 2_097_152, "Local response exceeded budget")
            return payload if raw else json.loads(payload)

    require(not get("/at/connection")["configured"], "Local UI unexpectedly connected to AT")
    require(not get("/model/connection")["enabled"], "Local UI unexpectedly enabled models")
    require(not get("/scheduling")["enabled"], "Local UI unexpectedly enabled scheduling")
    require(get("/container-runtime")["blockedReason"] == "CONTAINER_EXECUTION_DISABLED",
            "Local UI unexpectedly enabled source containers")
    require(get("/media-policy")["blockedReason"] == "MEDIA_EXECUTION_DISABLED",
            "Local UI unexpectedly enabled media requests")
    sources = get("/sources")
    source = next(item for item in sources if item["filename"] == "SeedHub.py")
    content = get(f"/sources/{source['id']}/artifacts/{source['artifactSha256']}", raw=True)
    expected = "743f3c1096c74619345b12b42fb2c759b4364e1f5b9a92d9ac02f50d58cb8272"
    require(sha(content) == source["artifactSha256"] == expected and len(content) == 36902,
            "Real directory source identity changed")
    runs = get(f"/sources/{source['id']}/probe-runs")
    require(len(runs) == 1 and runs[0]["executionMode"] == "static" and runs[0]["status"] == "COMPLETED",
            "Real source execution scope changed")
    statuses = [node["status"] for node in runs[0]["result"]["nodes"]]
    require(statuses.count("pass") == 3 and statuses.count("not_tested") == 41
            and len(statuses) == 44, "Real source evidence scope changed")
    for item in sources:
        if item["filename"].startswith("ui-receipt-"):
            require(get(f"/sources/{item['id']}/probe-runs") == [], "Directory sentinel was probed")
    knowledge = get("/knowledge?limit=12")
    nodes = get("/capability-nodes")
    require(len(knowledge["rules"]) == 12 and len(nodes["nodes"]) == 44, "Knowledge scope changed")

    files = [
        ROOT / "plugin-maintenance/server/pom.xml",
        ROOT / "plugin-maintenance/scripts/verify_directory_workbench.py",
        ROOT / "docs/plugin-maintenance/directory-evidence-progress-2026-11-09.md",
        ROOT / "docs/plugin-maintenance/directory-browser-first-failure.md",
        ROOT / ".claude/memory/handoff.md",
        ROOT / ".claude/memory/MEMORY.md",
        ROOT / ".claude/memory/conversations/2026-11-09.md",
        EVIDENCE / "browser-028-desktop-check.json",
        EVIDENCE / "browser-028-desktop-recheck.json",
        EVIDENCE / "browser-028-mobile-check.json",
        EVIDENCE / "backend-028-package.log",
        EVIDENCE / "frontend-unit-final.log",
        jar,
    ]
    ui = ROOT / "plugin-maintenance/web-ui"
    files += [ui / name for name in ("package.json", "package-lock.json", "vite.config.ts")]
    files += [ui / "src" / name for name in
              ("App.vue", "DirectoryImportPanel.vue", "directory.ts", "ProbeEvidencePanel.vue", "evidence.ts")]
    files += sorted((ui / "tests").glob("*"))
    files += sorted(EVIDENCE.glob("*.png"))
    frozen = [{"path": str(path.relative_to(ROOT.parent)), "sha256": sha(path.read_bytes()),
               "bytes": path.stat().st_size} for path in files]
    result = {
        "schemaVersion": 1, "version": "0.28.0-SNAPSHOT",
        "jarSha256": sha(jar.read_bytes()), "packagedUiFiles": packaged,
        "backendTests": 323, "frontendTests": 15, "browserChecks": browsers,
        "realSource": {"id": source["id"], "sha256": expected, "bytes": len(content),
                       "runId": runs[0]["id"], "pass": 3, "notTested": 41},
        "knowledge": {"rules": 12, "nodes": 44, "catalogSha256": knowledge["catalogSha256"]},
        "productionAtCalled": False, "realModelCalled": False, "sourceExecuted": False,
        "localOnly": True, "frozenFiles": frozen,
        "boundary": "Local directory import, static inspection and browser UI only; no live T4 or publication."
    }
    with output.open("xb") as handle:
        handle.write(json.dumps(result, ensure_ascii=False, indent=2).encode("utf-8") + b"\n")
    print(json.dumps({"receipt": str(output), "backendTests": 323, "frontendTests": 15,
                      "browserChecks": [5, 5], "frozenFiles": len(frozen)}, ensure_ascii=True))


if __name__ == "__main__":
    main()
