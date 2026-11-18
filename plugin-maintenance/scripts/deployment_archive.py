"""Carry a trusted cold instance and its exact Docker image to an offline host."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import instance_archive as instance

VERSION = "1.0.0"
KIND = "alist-tvbox-offline-deployment"
FILES = {"instance.zip", "image.tar", "runtime/deployment_archive.py", "runtime/instance_archive.py"}


def require(condition, code):
    instance.require(condition, code)


def docker(docker_path, arguments, failure):
    process = subprocess.run([str(docker_path), *map(str, arguments)], capture_output=True)
    require(process.returncode == 0, failure)
    return process.stdout


def image_identity(docker_path, ident):
    body = docker(docker_path, ["image", "inspect", ident], "DEPLOYMENT_IMAGE_INSPECT_FAILED")
    try:
        rows = json.loads(body)
        row = rows[0]
        require(len(rows) == 1 and row["Id"] == ident and row["Os"] == "linux"
                and isinstance(row["Architecture"], str) and row["Architecture"],
                "DEPLOYMENT_IMAGE_IDENTITY_MISMATCH")
        return {"id": ident, "os": row["Os"], "architecture": row["Architecture"]}
    except (ValueError, KeyError, IndexError, TypeError) as error:
        raise instance.ArchiveFailure("DEPLOYMENT_IMAGE_IDENTITY_MISMATCH") from error


def result(operation, manifest, sha, **fields):
    return {"ok": True, "operation": operation, "toolVersion": VERSION,
            "archiveSha256": sha, "privateData": True, "image": manifest["image"],
            "instanceArchiveSha256": manifest["files"]["instance.zip"]["sha256"],
            "applicationStarted": False, "requiresLocalOnlyStartup": True,
            "requiresCredentialRebinding": True, **fields}


def pack(archive_path, trusted_sha, output, docker_path="docker"):
    target = instance.fresh_target(output)
    original = instance.read_archive(archive_path, trusted_sha)
    image = original.get("imageId")
    require(isinstance(image, str) and image.startswith("sha256:")
            and instance.SHA.fullmatch(image[7:]), "DEPLOYMENT_IMAGE_REQUIRED")
    identity = image_identity(docker_path, image)
    with tempfile.TemporaryDirectory(prefix=".deployment-pack-", dir=target.parent) as directory:
        work = Path(directory)
        os.chmod(work, 0o700)
        image_tar = work / "image.tar"
        docker(docker_path, ["image", "save", "--output", image_tar, image], "DEPLOYMENT_IMAGE_SAVE_FAILED")
        require(image_tar.is_file() and image_tar.stat().st_size > 0, "DEPLOYMENT_IMAGE_SAVE_EMPTY")
        require(image_identity(docker_path, image) == identity, "DEPLOYMENT_IMAGE_IDENTITY_MISMATCH")
        manifest = {"schemaVersion": 1, "kind": KIND, "toolVersion": VERSION,
                    "privateData": True, "image": identity, "deployment": original["deployment"], "files": {}}
        sources = {"instance.zip": Path(archive_path), "image.tar": image_tar,
                   "runtime/deployment_archive.py": Path(__file__).resolve(),
                   "runtime/instance_archive.py": Path(instance.__file__).resolve()}
        prepared = work / "prepared.zip"
        with zipfile.ZipFile(prepared, "w", allowZip64=True) as archive:
            for name, path in sources.items():
                manifest["files"][name] = instance.add_file(archive, name, path, 0o600)
            require(manifest["files"]["instance.zip"]["sha256"] == trusted_sha,
                    "INSTANCE_IDENTITY_MISMATCH")
            archive.writestr(instance.MANIFEST, instance.json_bytes(manifest))
        sha = instance.hash_file(prepared)
        os.link(prepared, target)
        os.chmod(target, 0o600)
    return result("pack", manifest, sha, archiveBytes=target.stat().st_size, imageSaved=True)


def read_archive(path, trusted_sha, destination=None):
    require(isinstance(trusted_sha, str) and instance.SHA.fullmatch(trusted_sha),
            "INSTANCE_TRUSTED_IDENTITY_REQUIRED")
    require(instance.hash_file(path) == trusted_sha, "INSTANCE_IDENTITY_MISMATCH")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == FILES | {instance.MANIFEST},
                "DEPLOYMENT_ENTRIES_INVALID")
        require(archive.getinfo(instance.MANIFEST).file_size <= instance.MAX_MANIFEST,
                "INSTANCE_MANIFEST_TOO_LARGE")
        manifest = instance.json_read(archive.read(instance.MANIFEST))
        require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1
                and manifest.get("kind") == KIND and manifest.get("privateData") is True
                and manifest.get("deployment") in {"standalone", "at-embedded"},
                "DEPLOYMENT_MANIFEST_INVALID")
        image = manifest.get("image")
        require(isinstance(image, dict) and set(image) == {"id", "os", "architecture"}
                and isinstance(image["id"], str) and image["id"].startswith("sha256:")
                and instance.SHA.fullmatch(image["id"][7:]) and image["os"] == "linux"
                and isinstance(image["architecture"], str) and image["architecture"],
                "DEPLOYMENT_IMAGE_IDENTITY_MISMATCH")
        files = manifest.get("files")
        require(isinstance(files, dict) and set(files) == FILES, "DEPLOYMENT_ENTRIES_INVALID")
        for name, record in files.items():
            require(isinstance(record, dict) and type(record.get("bytes")) is int and record["bytes"] > 0
                    and isinstance(record.get("sha256"), str) and instance.SHA.fullmatch(record["sha256"])
                    and archive.getinfo(name).file_size == record["bytes"], "DEPLOYMENT_FILE_INVALID")
            target = destination / name if destination is not None else None
            if target is not None:
                target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            count = 0
            with archive.open(name) as stream:
                output = target.open("xb") if target is not None else None
                try:
                    while body := stream.read(instance.CHUNK):
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
                os.chmod(target, 0o600)
        return manifest


def restore(archive_path, trusted_sha, output, java="java", docker_path="docker"):
    target = instance.fresh_target(output)
    with tempfile.TemporaryDirectory(prefix=".deployment-restore-", dir=target.parent) as directory:
        work = Path(directory)
        os.chmod(work, 0o700)
        manifest = read_archive(archive_path, trusted_sha, work)
        instance_sha = manifest["files"]["instance.zip"]["sha256"]
        original = instance.read_archive(work / "instance.zip", instance_sha)
        require(original["imageId"] == manifest["image"]["id"]
                and original["deployment"] == manifest["deployment"], "DEPLOYMENT_INSTANCE_MISMATCH")
        # Loading a saved immutable ID neither chooses a mutable tag nor starts a service.
        docker(docker_path, ["image", "load", "--input", work / "image.tar"], "DEPLOYMENT_IMAGE_LOAD_FAILED")
        require(image_identity(docker_path, manifest["image"]["id"]) == manifest["image"],
                "DEPLOYMENT_IMAGE_IDENTITY_MISMATCH")
        target.mkdir(mode=0o700)
        try:
            restored = instance.restore(work / "instance.zip", target / "instance", instance_sha, java)
            shutil.copytree(work / "runtime", target / "runtime")
            (target / "deployment-recovery.json").write_bytes(instance.json_bytes({
                "archiveSha256": trusted_sha, "image": manifest["image"], "imageLoaded": True,
                "instance": restored, "applicationStarted": False,
                "requiresLocalOnlyStartup": True, "requiresCredentialRebinding": True,
            }))
        except Exception:
            shutil.rmtree(target)
            raise
    return result("restore", manifest, trusted_sha, imageLoaded=True, instanceRestored=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    create = sub.add_parser("pack")
    create.add_argument("--instance-archive", type=Path, required=True)
    create.add_argument("--trusted-instance-sha256", required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--docker", default="docker")
    inspect = sub.add_parser("inspect")
    inspect.add_argument("--archive", type=Path, required=True)
    inspect.add_argument("--trusted-sha256", required=True)
    recover = sub.add_parser("restore")
    recover.add_argument("--archive", type=Path, required=True)
    recover.add_argument("--trusted-sha256", required=True)
    recover.add_argument("--output", type=Path, required=True)
    recover.add_argument("--java", default="java")
    recover.add_argument("--docker", default="docker")
    options = parser.parse_args()
    try:
        if options.operation == "pack":
            value = pack(options.instance_archive, options.trusted_instance_sha256, options.output, options.docker)
        elif options.operation == "inspect":
            manifest = read_archive(options.archive, options.trusted_sha256)
            value = result("inspect", manifest, options.trusted_sha256)
        else:
            value = restore(options.archive, options.trusted_sha256, options.output, options.java, options.docker)
        print(json.dumps(value))
    except Exception as error:
        print(json.dumps({"ok": False, "code": str(error) if isinstance(error, instance.ArchiveFailure)
                          else "DEPLOYMENT_ARCHIVE_FAILED"}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
