"""Cold owned-instance archives; never start AT, AList, a spider or a model."""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import subprocess
import tempfile
import zipfile

KIND = "alist-tvbox-owned-instance"
MANIFEST = "manifest.json"
H2_ARCHIVE = "database.zip"
APPLICATION = "runtime/application.jar"
TOOL = "runtime/instance_archive.py"
CHUNK = 1024 * 1024
MAX_MANIFEST = 64 * 1024 * 1024
SHA = re.compile(r"[a-f0-9]{64}")
ROOT_NAME = re.compile(r"[a-z][a-z0-9-]{0,31}")
MAIN = "cn.har01d.atmaintenance.tools."


class ArchiveFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise ArchiveFailure(code)


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "INSTANCE_JSON_DUPLICATE")
        result[key] = value
    return result


def json_read(body):
    require(len(body) <= MAX_MANIFEST, "INSTANCE_MANIFEST_TOO_LARGE")
    try:
        return json.loads(body, object_pairs_hook=object_pairs)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ArchiveFailure("INSTANCE_JSON_INVALID") from error


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def hash_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while body := source.read(CHUNK):
            digest.update(body)
    return digest.hexdigest()


def relative(value):
    require(isinstance(value, str) and value and "\\" not in value and ":" not in value,
            "INSTANCE_PATH_INVALID")
    path = PurePosixPath(value)
    require(not path.is_absolute() and path.as_posix() == value
            and all(part not in {"", ".", ".."} for part in path.parts), "INSTANCE_PATH_INVALID")
    return path


def state_path(value, roots):
    path = relative(value)
    require(path.parts[0] in roots, "INSTANCE_ROOT_UNKNOWN")
    return path


def fresh_target(path):
    target = Path(path).absolute()
    require(not os.path.lexists(target), "INSTANCE_TARGET_MUST_NOT_EXIST")
    require(target.parent.is_dir(), "INSTANCE_TARGET_PARENT_MISSING")
    return target


def h2(java, jar, deployment, arguments):
    main = MAIN + ("EmbeddedDatabaseArchiveTool" if deployment == "at-embedded"
                   else "DatabaseArchiveTool")
    process = subprocess.run([
        str(java), "-Dfile.encoding=UTF-8", "-Dloader.main=" + main, "-cp", str(jar),
        "org.springframework.boot.loader.launch.PropertiesLauncher", *map(str, arguments),
    ], capture_output=True, encoding="utf-8", errors="replace")
    if process.returncode:
        try:
            code = json.loads(process.stderr.strip()).get("code", "")
        except ValueError:
            code = ""
        require(False, code if re.fullmatch(r"[A-Z][A-Z0-9_]{1,100}", code)
                else "INSTANCE_H2_FAILED")
    try:
        result = json.loads(process.stdout.strip())
    except ValueError as error:
        raise ArchiveFailure("INSTANCE_H2_RECEIPT_INVALID") from error
    require(result.get("ok") is True, "INSTANCE_H2_RECEIPT_INVALID")
    return result


def configuration(path):
    config = json_read(Path(path).read_bytes())
    require(isinstance(config, dict) and set(config) <= {
        "schemaVersion", "deployment", "roots", "mounts", "database", "sqlite",
        "imageId", "credentialReferences",
    }, "INSTANCE_CONFIG_INVALID")
    require(config.get("schemaVersion") == 1
            and config.get("deployment") in {"standalone", "at-embedded"},
            "INSTANCE_CONFIG_INVALID")
    roots = config.get("roots")
    require(isinstance(roots, dict) and roots and all(
        ROOT_NAME.fullmatch(name) and isinstance(value, str) for name, value in roots.items()
    ), "INSTANCE_ROOTS_INVALID")
    resolved = {name: Path(value).absolute() for name, value in roots.items()}
    require(all(value.is_dir() and not value.is_symlink() for value in resolved.values()),
            "INSTANCE_ROOT_MISSING")
    real = [value.resolve() for value in resolved.values()]
    require(all(not a.is_relative_to(b) for a in real for b in real if a != b)
            and len(set(real)) == len(real), "INSTANCE_ROOTS_OVERLAP")
    config["database"] = state_path(config.get("database"), roots).as_posix()
    require(config["database"].endswith(".mv.db"), "INSTANCE_DATABASE_INVALID")
    sqlite = config.setdefault("sqlite", [])
    require(isinstance(sqlite, list) and len(set(sqlite)) == len(sqlite),
            "INSTANCE_SQLITE_INVALID")
    for value in sqlite:
        state_path(value, roots)
    require(config["database"] not in sqlite, "INSTANCE_SQLITE_INVALID")
    mounts = config.setdefault("mounts", {})
    require(isinstance(mounts, dict), "INSTANCE_MOUNTS_INVALID")
    for mount, value in mounts.items():
        require(isinstance(mount, str) and mount.startswith("/")
                and PurePosixPath(mount).as_posix() == mount and ".." not in mount.split("/"),
                "INSTANCE_MOUNTS_INVALID")
        state_path(value, roots)
        require(resolve_state(value, resolved).exists(), "INSTANCE_MOUNT_MISSING")
    references = config.setdefault("credentialReferences", [])
    require(isinstance(references, list) and all(
        isinstance(value, str) and re.fullmatch(r"[A-Z_][A-Z0-9_]*", value)
        for value in references), "INSTANCE_CREDENTIAL_REFERENCES_INVALID")
    image = config.setdefault("imageId", None)
    require(image is None or (isinstance(image, str)
                              and re.fullmatch(r"sha256:[a-f0-9]{64}", image)),
            "INSTANCE_IMAGE_INVALID")
    return config, resolved


def resolve_state(value, roots):
    path = state_path(value, roots)
    result = roots[path.parts[0]].joinpath(*path.parts[1:])
    require(result.resolve().is_relative_to(roots[path.parts[0]].resolve()),
            "INSTANCE_PATH_OUTSIDE_ROOT")
    return result


def excluded_database_files(config):
    database = config["database"]
    stem = database[:-len(".mv.db")]
    result = {database, *(stem + suffix for suffix in
                         (".lock.db", ".trace.db", ".temp.db", ".newFile", ".tempFile"))}
    for value in config["sqlite"]:
        result.update(value + suffix for suffix in ("-wal", "-shm", "-journal"))
    return result


def collect(roots, excluded):
    result = {}
    for name, root in sorted(roots.items()):
        for directory, directories, files in os.walk(root, followlinks=False):
            parent = Path(directory)
            for path in (parent, *(parent / item for item in sorted(files + directories))):
                local = name + ("/" + path.relative_to(root).as_posix() if path != root else "")
                if local in result or local in excluded:
                    continue
                info = path.lstat()
                require(stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode),
                        "INSTANCE_REGULAR_FILES_REQUIRED")
                relative(local)
                directory_entry = stat.S_ISDIR(info.st_mode)
                result[local] = (path, 0 if directory_entry else info.st_size,
                                 0 if directory_entry else info.st_mtime_ns,
                                 stat.S_IMODE(info.st_mode), directory_entry)
    return result


def sqlite_schema(connection):
    require(connection.execute("PRAGMA quick_check").fetchall() == [("ok",)],
            "INSTANCE_SQLITE_INTEGRITY_FAILED")
    names = [row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    return {"userVersion": connection.execute("PRAGMA user_version").fetchone()[0],
            "tables": {name: connection.execute(
                'SELECT count(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0]
                       for name in names}}


def sqlite_snapshot(source, destination):
    require(source.is_file() and not source.is_symlink(), "INSTANCE_SQLITE_MISSING")
    with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True, timeout=10)) as old:
        old.execute("BEGIN")
        expected = sqlite_schema(old)
        with closing(sqlite3.connect(destination)) as new:
            old.backup(new)
            require(sqlite_schema(new) == expected, "INSTANCE_SQLITE_SNAPSHOT_MISMATCH")
    return expected


def add_file(archive, name, source, mode):
    before = source.stat()
    entry = zipfile.ZipInfo(name)
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | mode) << 16
    digest = hashlib.sha256()
    size = 0
    with source.open("rb") as stream, archive.open(entry, "w", force_zip64=True) as output:
        while body := stream.read(CHUNK):
            output.write(body)
            digest.update(body)
            size += len(body)
    after = source.stat()
    require(size == before.st_size == after.st_size
            and before.st_mtime_ns == after.st_mtime_ns, "INSTANCE_SOURCE_CHANGED")
    return {"bytes": size, "sha256": digest.hexdigest(), "mode": mode}


def receipt(operation, manifest, sha):
    return {"ok": True, "operation": operation, "archiveSha256": sha, "privateData": True,
            "deployment": manifest["deployment"], "fileCount": len(manifest["files"]),
            "sqliteCount": len(manifest["sqlite"]), "applicationStarted": False,
            "requiresLocalOnlyStartup": True, "requiresCredentialRebinding": True}


def backup(config_path, output, java, jar):
    config, roots = configuration(config_path)
    target = fresh_target(output)
    require(all(not target.resolve().is_relative_to(root.resolve()) for root in roots.values()),
            "INSTANCE_ARCHIVE_OUTSIDE_ROOTS_REQUIRED")
    jar = Path(jar).resolve(strict=True)
    database = resolve_state(config["database"], roots)
    excluded = excluded_database_files(config)
    with tempfile.TemporaryDirectory(prefix=".instance-backup-", dir=target.parent) as work_name:
        work = Path(work_name)
        os.chmod(work, 0o700)
        database_zip = work / H2_ARCHIVE
        h2(java, jar, config["deployment"], ["backup", "--database", database, "--output", database_zip])
        inputs = collect(roots, excluded)
        sqlite = {}
        snapshots = {}
        for number, name in enumerate(config["sqlite"]):
            path = work / ("sqlite-" + str(number) + ".db")
            sqlite[name] = sqlite_snapshot(resolve_state(name, roots), path)
            snapshots[name] = path
        manifest = {
            "schemaVersion": 1, "kind": KIND, "privateData": True,
            "createdAt": datetime.now(timezone.utc).isoformat(), "deployment": config["deployment"],
            "database": config["database"], "sqlite": sqlite, "roots": sorted(roots),
            "mounts": config["mounts"], "imageId": config["imageId"],
            "credentialReferences": config["credentialReferences"], "files": {}, "directories": {},
        }
        prepared = work / "prepared.zip"
        with zipfile.ZipFile(prepared, "w", allowZip64=True) as archive:
            files = manifest["files"]
            files[H2_ARCHIVE] = add_file(archive, H2_ARCHIVE, database_zip, 0o600)
            files[APPLICATION] = add_file(archive, APPLICATION, jar, 0o600)
            files[TOOL] = add_file(archive, TOOL, Path(__file__).resolve(), 0o700)
            for name, (source, _, _, mode, directory) in sorted(inputs.items()):
                if directory:
                    manifest["directories"]["state/" + name] = mode
                else:
                    files["state/" + name] = add_file(
                        archive, "state/" + name, snapshots.get(name, source), mode)
            require(collect(roots, excluded) == inputs, "INSTANCE_SOURCE_CHANGED")
            body = json_bytes(manifest)
            require(len(body) <= MAX_MANIFEST, "INSTANCE_MANIFEST_TOO_LARGE")
            archive.writestr(MANIFEST, body)
        sha = hash_file(prepared)
        # Publish only our new file, without replacing a concurrent destination.
        os.link(prepared, target)
        os.chmod(target, 0o600)
        return receipt("backup", manifest, sha)


def verified_manifest(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)) and MANIFEST in names, "INSTANCE_ENTRIES_INVALID")
    info = archive.getinfo(MANIFEST)
    require(info.file_size <= MAX_MANIFEST, "INSTANCE_MANIFEST_TOO_LARGE")
    manifest = json_read(archive.read(info))
    require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1
            and manifest.get("kind") == KIND and manifest.get("privateData") is True
            and manifest.get("deployment") in {"standalone", "at-embedded"},
            "INSTANCE_MANIFEST_INVALID")
    files, directories, roots = manifest.get("files"), manifest.get("directories"), manifest.get("roots")
    require(isinstance(files, dict) and isinstance(directories, dict)
            and isinstance(roots, list) and roots and len(roots) == len(set(roots))
            and all(isinstance(name, str) and ROOT_NAME.fullmatch(name) for name in roots),
            "INSTANCE_MANIFEST_INVALID")
    require(set(names) == {MANIFEST, *files} and {H2_ARCHIVE, APPLICATION, TOOL} <= files.keys(),
            "INSTANCE_ENTRIES_INVALID")
    require(all("state/" + name in directories for name in roots), "INSTANCE_ROOTS_INVALID")
    for name, mode in directories.items():
        path = relative(name)
        require(path.parts[0] == "state" and len(path.parts) >= 2
                and path.parts[1] in roots and type(mode) is int and 0 <= mode <= 0o777,
                "INSTANCE_DIRECTORY_INVALID")
    for name, record in files.items():
        path = relative(name)
        require(name in {H2_ARCHIVE, APPLICATION, TOOL} or (
            len(path.parts) >= 3 and path.parts[0] == "state" and path.parts[1] in roots
        ), "INSTANCE_ENTRIES_INVALID")
        require(isinstance(record, dict) and type(record.get("bytes")) is int
                and record["bytes"] >= 0 and isinstance(record.get("sha256"), str)
                and SHA.fullmatch(record["sha256"]) and type(record.get("mode")) is int
                and 0 <= record["mode"] <= 0o777, "INSTANCE_FILE_INVALID")
        entry = archive.getinfo(name)
        require(entry.file_size == record["bytes"] and not entry.is_dir()
                and stat.S_IFMT(entry.external_attr >> 16) in {0, stat.S_IFREG},
                "INSTANCE_FILE_INVALID")
    database = state_path(manifest.get("database"), roots)
    require(database.as_posix().endswith(".mv.db")
            and "state/" + database.as_posix() not in files, "INSTANCE_DATABASE_INVALID")
    require(isinstance(manifest.get("sqlite"), dict), "INSTANCE_SQLITE_INVALID")
    for name in manifest["sqlite"]:
        state_path(name, roots)
        require("state/" + name in files, "INSTANCE_SQLITE_MISSING")
    return manifest


def read_archive(path, trusted_sha, destination=None):
    require(isinstance(trusted_sha, str) and SHA.fullmatch(trusted_sha),
            "INSTANCE_TRUSTED_IDENTITY_REQUIRED")
    require(hash_file(path) == trusted_sha, "INSTANCE_IDENTITY_MISMATCH")
    with zipfile.ZipFile(path) as archive:
        manifest = verified_manifest(archive)
        if destination is not None:
            for name in sorted(manifest["directories"], key=lambda item: len(relative(item).parts)):
                destination.joinpath(*relative(name).parts).mkdir(parents=True, exist_ok=True)
        for name, record in manifest["files"].items():
            target = destination.joinpath(*relative(name).parts) if destination is not None else None
            if target is not None:
                target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            count = 0
            with archive.open(name) as source:
                output = target.open("xb") if target is not None else None
                try:
                    while body := source.read(CHUNK):
                        count += len(body)
                        require(count <= record["bytes"], "INSTANCE_FILE_SIZE_MISMATCH")
                        digest.update(body)
                        if output is not None:
                            output.write(body)
                finally:
                    if output is not None:
                        output.close()
            require(count == record["bytes"] and digest.hexdigest() == record["sha256"],
                    "INSTANCE_FILE_HASH_MISMATCH")
            if target is not None:
                os.chmod(target, record["mode"] & 0o777)
        return manifest


def restore(archive, output, trusted_sha, java, jar=None):
    target = fresh_target(output)
    with tempfile.TemporaryDirectory(prefix=".instance-restore-", dir=target.parent) as work_name:
        work = Path(work_name)
        os.chmod(work, 0o700)
        manifest = read_archive(archive, trusted_sha, work)
        application = work / APPLICATION
        if jar is not None:
            require(hash_file(jar) == manifest["files"][APPLICATION]["sha256"],
                    "INSTANCE_JAR_IDENTITY_MISMATCH")
            application = Path(jar)
        h2_result = h2(java, application, manifest["deployment"], [
            "restore", "--archive", work / H2_ARCHIVE, "--destination", work / "h2",
            "--trusted-sha256", manifest["files"][H2_ARCHIVE]["sha256"],
        ])
        db_name = "atv.mv.db" if manifest["deployment"] == "at-embedded" else "maintenance.mv.db"
        database = work / "state" / manifest["database"]
        database.parent.mkdir(parents=True, exist_ok=True)
        (work / "h2" / db_name).rename(database)
        for name, expected in manifest["sqlite"].items():
            with closing(sqlite3.connect(
                    (work / "state" / name).as_uri() + "?mode=ro&immutable=1", uri=True)) as connection:
                require(sqlite_schema(connection) == expected, "INSTANCE_SQLITE_SCHEMA_MISMATCH")
        (work / H2_ARCHIVE).unlink()
        shutil.rmtree(work / "h2")
        config = {key: manifest[key] for key in (
            "schemaVersion", "deployment", "database", "mounts", "imageId", "credentialReferences")}
        config["roots"] = {name: str(target / "state" / name) for name in manifest["roots"]}
        config["sqlite"] = list(manifest["sqlite"])
        (work / "instance.json").write_bytes(json_bytes(config))
        (work / "recovery-manifest.json").write_bytes(json_bytes({
            "archiveSha256": trusted_sha, "instance": manifest, "database": h2_result,
            "applicationStarted": False, "requiresLocalOnlyStartup": True,
            "requiresCredentialRebinding": True,
        }))
        # No existing directory is touched, even when another restore wins the name.
        target.mkdir(mode=0o700)
        try:
            for item in list(work.iterdir()):
                shutil.move(str(item), target / item.name)
            for name, mode in sorted(manifest["directories"].items(), reverse=True):
                os.chmod(target / name, mode)
        except Exception:
            shutil.rmtree(target)
            raise
        return receipt("restore", manifest, trusted_sha)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    create = sub.add_parser("backup")
    create.add_argument("--config", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--jar", type=Path, required=True)
    create.add_argument("--java", default="java")
    inspect = sub.add_parser("inspect")
    inspect.add_argument("--archive", type=Path, required=True)
    inspect.add_argument("--trusted-sha256", required=True)
    recover = sub.add_parser("restore")
    recover.add_argument("--archive", type=Path, required=True)
    recover.add_argument("--output", type=Path, required=True)
    recover.add_argument("--trusted-sha256", required=True)
    recover.add_argument("--java", default="java")
    recover.add_argument("--jar", type=Path)
    options = parser.parse_args()
    try:
        if options.operation == "backup":
            result = backup(options.config, options.output, options.java, options.jar)
        elif options.operation == "inspect":
            manifest = read_archive(options.archive, options.trusted_sha256)
            result = receipt("inspect", manifest, options.trusted_sha256)
        else:
            result = restore(options.archive, options.output, options.trusted_sha256,
                             options.java, options.jar)
        print(json.dumps(result))
    except Exception as error:
        code = str(error) if isinstance(error, ArchiveFailure) else "INSTANCE_ARCHIVE_FAILED"
        print(json.dumps({"ok": False, "code": code}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
