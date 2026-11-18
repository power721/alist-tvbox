"""Read only explicitly superseded broker owners; never scan or stop the host."""
from __future__ import annotations

import json
from pathlib import Path, PurePosixPath


def superseded_roots(previous, current):
    if previous["ssh"] != current["ssh"]:
        raise ValueError("BROKER_RETIREMENT_HOST_CHANGED")

    def roots(document):
        routes = document["runners"]
        if document["schemaVersion"] != 1 or not 0 < len(routes) <= 64:
            raise ValueError("BROKER_RETIREMENT_CONFIG_INVALID")
        result = set()
        for route in routes:
            socket = route["remoteSocket"]
            path = PurePosixPath(socket)
            if (not path.is_absolute() or str(path) != socket or ".." in path.parts
                    or path.name != "executor.sock"):
                raise ValueError("BROKER_RETIREMENT_CONFIG_INVALID")
            result.add(str(path.parent))
        return result

    return sorted(roots(previous) - roots(current))


def broker_processes(retire_roots, keep_roots, *, proc=Path("/proc")):
    requested, keep = set(retire_roots), set(keep_roots)
    if len(requested) > 64 or requested & keep:
        raise ValueError("BROKER_RETIREMENT_SCOPE_INVALID")
    rows = []
    for value in sorted(requested):
        root = Path(value)
        row = {"root": value}
        try:
            if not root.is_absolute() or root.resolve() != root or root.is_symlink():
                raise ValueError("BROKER_RETIREMENT_ROOT_INVALID")
            owner_body = (root / "service-owner.json").read_bytes()
            if len(owner_body) > 8192:
                raise ValueError("BROKER_RETIREMENT_OWNER_INVALID")
            pid = json.loads(owner_body)["pid"]
            if type(pid) is not int or pid <= 0:
                raise ValueError("BROKER_RETIREMENT_OWNER_INVALID")
            process = proc / str(pid)
            args = (process / "cmdline").read_bytes().split(b"\0")
            if (len(args) != 7 or args[-1] != b"" or args[1] != b"-I"
                    or args[3:5] != [b"serve", b"--config"]
                    or Path(args[2].decode()) != root / "managed_broker_service.py"
                    or Path(args[5].decode()) != root / "broker-service.json"):
                raise ValueError("BROKER_RETIREMENT_PROCESS_CHANGED")
            state = (process / "stat").read_text().rpartition(")")[2].split()
            if len(state) < 20 or state[0] == "Z" or not state[19].isdigit():
                raise ValueError("BROKER_RETIREMENT_PROCESS_CHANGED")
            row.update(pid=pid, startTicks=state[19])
        except (OSError, ValueError, KeyError, TypeError) as error:
            row["skipped"] = (str(error) if isinstance(error, ValueError)
                              and str(error).startswith("BROKER_RETIREMENT_") else type(error).__name__)
        rows.append(row)
    return rows
