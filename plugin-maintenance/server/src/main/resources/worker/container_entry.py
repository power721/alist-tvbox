"""Trusted image entrypoint: observe Linux controls, then run the fixed bundled fixture."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path("/opt/at-maintenance")
FILES = {"Atvp.py", "bootstrap.py", "demo.py", "probe_fixture.py", "t4cenc.py", "t4worker.py"}


def require(condition):
    if not condition:
        raise ValueError("CONTAINER_ENTRY_CHECK_FAILED")


def digest(body):
    return hashlib.sha256(body).hexdigest()


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def validate_request(body, manifest_bytes):
    require(len(body) <= 2048)
    request = json.loads(body, object_pairs_hook=unique_fields)
    require(isinstance(request, dict) and set(request) == {"jobId", "sourceSha256", "runtimeSha256"})
    require(isinstance(request["jobId"], str) and re.fullmatch(
        r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", request["jobId"]))
    manifest = json.loads(manifest_bytes, object_pairs_hook=unique_fields)
    require(request["runtimeSha256"] == digest(manifest_bytes)
            and request["sourceSha256"] == manifest["fixtureSha256"] and set(manifest["files"]) == FILES)
    return request, manifest


def validate_controls(observed):
    require(observed["uid"] == observed["gid"] == 65532)
    require(observed["capEffective"] == 0 and observed["noNewPrivileges"] == 1 and observed["seccomp"] == 2)
    require(observed["memoryMax"] == 268435456 and observed["swapMax"] == 0 and observed["pidsMax"] == 64)
    require(observed["cpuQuota"] == 50000 and observed["cpuPeriod"] == 100000)
    require(observed["interfaces"] == ["lo"] and observed["rootReadOnly"])
    require(set(observed["workMountOptions"]) >= {"rw", "noexec", "nosuid", "nodev"})
    require(0 < observed["workSizeBytes"] <= 33554432)
    require(observed["coreLimit"] == [0, 0] and observed["openFileLimit"] == [128, 128])
    return {**observed, "allChecksPassed": True}


def observe_controls():
    import resource

    status = {}
    for line in Path("/proc/self/status").read_text(encoding="ascii").splitlines():
        key, value = line.split(":", 1)
        status[key] = value.strip()
    mounts = {}
    for line in Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        mounts[fields[4]] = fields[5].split(",")
    cgroup = Path("/sys/fs/cgroup")
    quota, period = (cgroup / "cpu.max").read_text(encoding="ascii").split()
    interfaces = sorted(line.split(":", 1)[0].strip()
                        for line in Path("/proc/net/dev").read_text(encoding="ascii").splitlines() if ":" in line)
    work_stat = os.statvfs("/work")
    return validate_controls({
        "uid": os.getuid(), "gid": os.getgid(), "capEffective": int(status["CapEff"], 16),
        "noNewPrivileges": int(status["NoNewPrivs"]), "seccomp": int(status["Seccomp"]),
        "memoryMax": int((cgroup / "memory.max").read_text(encoding="ascii")),
        "swapMax": int((cgroup / "memory.swap.max").read_text(encoding="ascii")),
        "pidsMax": int((cgroup / "pids.max").read_text(encoding="ascii")),
        "cpuQuota": int(quota), "cpuPeriod": int(period), "interfaces": interfaces,
        "rootReadOnly": "ro" in mounts["/"], "workMountOptions": mounts["/work"],
        "workSizeBytes": work_stat.f_blocks * work_stat.f_frsize,
        "coreLimit": list(resource.getrlimit(resource.RLIMIT_CORE)),
        "openFileLimit": list(resource.getrlimit(resource.RLIMIT_NOFILE)),
    })


def execute():
    root = ROOT / "runtime"
    manifest_bytes = (root / "manifest.json").read_bytes()
    request, manifest = validate_request(sys.stdin.buffer.read(2049), manifest_bytes)
    controls = observe_controls()
    # No source, command, path, credentials or EXT can be supplied by a request.
    work = Path("/work") / request["jobId"]
    work.mkdir(mode=0o700)
    for name in FILES:
        body = (root / name).read_bytes()
        record = manifest["files"][name]
        require(digest(body) == record["sha256"] and len(body) == record["bytes"])
        (work / name).write_bytes(body)
    (work / "manifest.json").write_bytes(manifest_bytes)
    env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8", "HOME": str(work),
           "TMPDIR": str(work), "PYTHONDONTWRITEBYTECODE": "1"}
    result = subprocess.run([sys.executable, "-I", str(work / "probe_fixture.py"),
                             request["sourceSha256"], request["runtimeSha256"]],
                            cwd=work, env=env, input=b"", stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=17, check=False)
    require(result.returncode == 0 and len(result.stdout) <= 131072 and len(result.stderr) <= 65536)
    evidence = json.loads(result.stdout, object_pairs_hook=unique_fields)
    require(evidence["evidenceClass"] == "offline_fixture"
            and evidence["receipt"]["sourceBytesSha256"] == request["sourceSha256"]
            and evidence["receipt"]["loadedTextSha256"] == request["sourceSha256"]
            and evidence["receipt"]["runtimeSha256"] == request["runtimeSha256"])
    return {"schemaVersion": 1, "authority": "container_entry_observed", **request,
            "entrySha256": digest(Path(__file__).read_bytes()), "controls": controls,
            "runtimeResult": evidence,
            "boundary": "Bundled fixture only; Linux controls observed here, not an AT/T4 or unknown-source verdict"}


def main():
    try:
        print(json.dumps(execute(), ensure_ascii=True, separators=(",", ":")))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        # Do not expose source, engine diagnostics, filesystem contents or credentials.
        print('{"ok":false,"code":"CONTAINER_ENTRY_FAILED"}')
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
