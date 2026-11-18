"""Fixed acceptance workloads. Executed only by python -I -c inside the pinned test image."""
from __future__ import annotations

import errno
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

CGROUP = Path("/sys/fs/cgroup")
MODES = {"memory", "pids", "cpu", "timeout"}
MEMORY_CHILD = """
blocks = []
for _ in range(96):
    block = bytearray(4 * 1024 * 1024)
    block[::4096] = b'x' * 1024
    blocks.append(block)
"""


def counters(name):
    values = {}
    for line in (CGROUP / name).read_text(encoding="ascii").splitlines():
        key, value = line.split()
        if not re.fullmatch(r"[a-z_]+", key) or not value.isdecimal() or key in values:
            raise ValueError("RESOURCE_COUNTER_INVALID")
        values[key] = int(value)
    return values


def memory_observation():
    before = counters("memory.events")
    child = subprocess.Popen([sys.executable, "-I", "-c", MEMORY_CHILD],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        child.wait(timeout=8)
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=2)
    return {"before": before, "after": counters("memory.events"), "childExitCode": child.returncode}


def pids_observation():
    before = counters("pids.events")
    children = []
    refused = None
    try:
        # Only the parent forks, with a finite cap even if the kernel limit is broken.
        for _ in range(72):
            try:
                pid = os.fork()
            except OSError as error:
                refused = error.errno
                break
            if pid == 0:
                try:
                    time.sleep(10)
                finally:
                    os._exit(0)
            children.append(pid)
        current = int((CGROUP / "pids.current").read_text(encoding="ascii"))
        after = counters("pids.events")
    finally:
        for pid in children:
            os.kill(pid, signal.SIGKILL)
        for pid in children:
            os.waitpid(pid, 0)
    return {"before": before, "after": after, "forkErrno": refused,
            "expectedErrno": errno.EAGAIN, "childrenCreated": len(children), "pidsAtRefusal": current}


def cpu_observation():
    before = counters("cpu.stat")
    started = time.monotonic()
    value = 0
    while time.monotonic() - started < 2:
        value = (value * 33 + 17) % 104729
    return {"before": before, "after": counters("cpu.stat"),
            "elapsedMilliseconds": int((time.monotonic() - started) * 1000)}


def execute():
    # Unit tests may import this file; no workload is allowed before the image/control gates.
    if sys.platform != "linux" or sys.argv[0] != "-c" or len(sys.argv) != 3 or sys.argv[1] not in MODES:
        raise ValueError("RESOURCE_IMAGE_REQUIRED")
    entry_path = Path("/opt/at-maintenance/container_entry.py")
    if hashlib.sha256(entry_path.read_bytes()).hexdigest() != sys.argv[2]:
        raise ValueError("RESOURCE_ENTRY_MISMATCH")
    spec = importlib.util.spec_from_file_location("trusted_resource_entry", entry_path)
    entry = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(entry)
    manifest = Path("/opt/at-maintenance/runtime/manifest.json").read_bytes()
    request, _ = entry.validate_request(sys.stdin.buffer.read(2049), manifest)
    controls = entry.observe_controls()
    mode = sys.argv[1]
    if mode == "timeout":
        time.sleep(12)
        observation = {"delayCompleted": True}
    else:
        observation = {"memory": memory_observation, "pids": pids_observation, "cpu": cpu_observation}[mode]()
    return {"schemaVersion": 1, "authority": "fixed_resource_probe_observed", **request,
            "scenario": mode, "entrySha256": sys.argv[2], "controls": controls, "observation": observation}


if __name__ == "__main__":
    try:
        print(json.dumps(execute(), ensure_ascii=True, separators=(",", ":")))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('{"ok":false,"code":"RESOURCE_PROBE_FAILED"}')
        raise SystemExit(1) from None
