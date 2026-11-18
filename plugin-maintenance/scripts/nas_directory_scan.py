"""Read-only POSIX inventory. Source bytes are hashed, never returned or executed."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import PurePosixPath
import stat
import sys

LIMITS = {"entries": 2000, "sources": 500, "depth": 8,
          "fileBytes": 1048576, "totalBytes": 16777216}
EXCLUDED = {"venv", "node_modules", "__pycache__"}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def absolute_parts(path):
    require(isinstance(path, str) and path.startswith("/") and len(path) <= 2048
            and "\\" not in path and not any(ord(c) < 32 for c in path),
            "INVENTORY_PATH_INVALID")
    parts = path.split("/")[1:]
    require(parts and all(p and p not in {".", ".."} for p in parts), "INVENTORY_PATH_INVALID")
    return parts


def directory_fd(path):
    parts = absolute_parts(path)
    require(os.name == "posix" and hasattr(os, "O_NOFOLLOW"), "INVENTORY_POSIX_REQUIRED")
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in parts:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def scan(path):
    result = {"complete": True, "visitedEntries": 0, "ignoredEntries": 0,
              "sourceBytesRead": 0, "limits": dict(LIMITS), "blockedDirectories": 0}
    files = []

    def visit(fd, prefix, depth):
        # Stop iteration at the budget; never materialize an unbounded directory.
        with os.scandir(fd) as entries:
            for entry in entries:
                if result["visitedEntries"] >= LIMITS["entries"]:
                    result["complete"] = False
                    return
                result["visitedEntries"] += 1
                name = entry.name
                if (name.startswith(".") or name in EXCLUDED or len(name) > 200
                        or "\\" in name or any(ord(c) < 32 for c in name)):
                    result["ignoredEntries"] += 1
                    continue
                relative = prefix + name
                try:
                    info = entry.stat(follow_symlinks=False)
                    if stat.S_ISDIR(info.st_mode):
                        if depth >= LIMITS["depth"]:
                            result["blockedDirectories"] += 1
                            result["complete"] = False
                            continue
                        child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                        try:
                            actual = os.fstat(child)
                            require((actual.st_dev, actual.st_ino) == (info.st_dev, info.st_ino),
                                    "INVENTORY_DIRECTORY_CHANGED")
                            visit(child, relative + "/", depth + 1)
                        finally:
                            os.close(child)
                        continue
                    suffix = PurePosixPath(name).suffix
                    if suffix not in {".py", ".js"}:
                        if stat.S_ISLNK(info.st_mode):
                            result["blockedDirectories"] += 1
                            result["complete"] = False
                        result["ignoredEntries"] += 1
                        continue
                    if len(files) >= LIMITS["sources"]:
                        result["complete"] = False
                        return
                    row = {"path": relative, "kind": "python" if suffix == ".py" else "javascript",
                           "bytes": info.st_size, "sha256": None, "status": "unreadable"}
                    files.append(row)
                    if stat.S_ISLNK(info.st_mode):
                        row["status"] = "symlink"
                        continue
                    if not stat.S_ISREG(info.st_mode):
                        row["status"] = "not_regular"
                        continue
                    if not 0 < info.st_size <= LIMITS["fileBytes"]:
                        row["status"] = "size_blocked"
                        continue
                    if result["sourceBytesRead"] + info.st_size + 1 > LIMITS["totalBytes"]:
                        row["status"] = "budget_blocked"
                        result["complete"] = False
                        continue
                    stream_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                    with os.fdopen(stream_fd, "rb") as stream:
                        before = os.fstat(stream.fileno())
                        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                                or (before.st_dev, before.st_ino, before.st_size)
                                != (info.st_dev, info.st_ino, info.st_size)):
                            row["status"] = "changed_or_linked"
                            continue
                        body = stream.read(info.st_size + 1)
                        result["sourceBytesRead"] += len(body)
                        after = os.fstat(stream.fileno())
                        linked = os.stat(name, dir_fd=fd, follow_symlinks=False)
                        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
                        if (len(body) != info.st_size or identity(before) != identity(after)
                                or identity(after) != identity(linked)):
                            row["status"] = "changed_or_linked"
                            continue
                    try:
                        text = body.decode("utf-8-sig")
                    except UnicodeError:
                        row["status"] = "invalid_utf8"
                        continue
                    if not text.strip() or "\0" in text:
                        row["status"] = "binary_or_empty"
                        continue
                    row.update(status="hashed", sha256=hashlib.sha256(body).hexdigest())
                except (OSError, ValueError):
                    result["complete"] = False
                    result["blockedDirectories"] += 1

    root = directory_fd(path)
    try:
        visit(root, "", 0)
    finally:
        os.close(root)
    files.sort(key=lambda row: row["path"])
    return {"scan": result, "files": files}


def main():
    payload = sys.stdin.buffer.read(4097)
    require(len(payload) <= 4096, "INVENTORY_INPUT_LIMIT")
    value = json.loads(payload)
    require(isinstance(value, dict) and set(value) == {"hostDirectory"}, "INVENTORY_INPUT_INVALID")
    print(json.dumps(scan(value["hostDirectory"]), ensure_ascii=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit("INVENTORY_SCAN_FAILED") from None
