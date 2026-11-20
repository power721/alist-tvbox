"""Pack and install a private, pinned same-host T4 bundle without old instance data."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import uuid

DOCKER = ["/usr/bin/docker", "--host", "unix:///var/run/docker.sock"]
MANIFEST = "install-bundle.json"
RUNTIME_MANIFEST = "server/src/main/resources/worker/managed-runtime.json"
FIXTURE_ROOT = "server/src/main/resources/worker/runtime/"


def require(valid, code):
    if not valid:
        raise ValueError(code)


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative(name):
    require(isinstance(name, str), "INSTALL_BUNDLE_PATH_INVALID")
    value = PurePosixPath(name)
    require(value.as_posix() == name and not value.is_absolute()
            and ".." not in value.parts and "\\" not in name and bool(value.parts), "INSTALL_BUNDLE_PATH_INVALID")
    return value


def image_id(value):
    require(isinstance(value, str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value), "INSTALL_BUNDLE_IMAGE_INVALID")
    return value


def load_installer(root):
    spec = importlib.util.spec_from_file_location("bundle_local_t4", Path(root) / "scripts/install_local_t4.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def runtime_files(root):
    root = Path(root).resolve()
    manifest = json.loads((root / RUNTIME_MANIFEST).read_bytes())
    require(sha(root / (FIXTURE_ROOT + "manifest.json")) == manifest["runtimeSha256"],
            "INSTALL_BUNDLE_RUNTIME_CHANGED")
    files = {RUNTIME_MANIFEST, FIXTURE_ROOT + "manifest.json"}
    files.update(manifest["files"])
    files.update("scripts/" + name for name in (*load_installer(root).FILES, "install_local_t4.py"))
    # Worker payload files live in the pinned images; the host needs their manifest identity only.
    for name, record in manifest["files"].items():
        relative(name)
        require(sha(root / name) == record["sha256"], "INSTALL_BUNDLE_RUNTIME_CHANGED")
    vendor = root / "vendor"
    require(vendor.is_dir() and (vendor / "m3u8").is_dir() and (vendor / "Crypto").is_dir(),
            "INSTALL_BUNDLE_VENDOR_REQUIRED")
    for entry in vendor.iterdir():
        require(not entry.is_symlink(), "INSTALL_BUNDLE_PATH_INVALID")
        if entry.name in {"m3u8", "Crypto"} or entry.name.startswith(("m3u8-", "pycryptodome-")):
            files.update(path.relative_to(root).as_posix() for path in entry.rglob("*")
                         if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    for name in files:
        relative(name)
        path = root / name
        require(path.is_file() and path.resolve() == path and not path.is_symlink(), "INSTALL_BUNDLE_PATH_INVALID")
    return sorted(files)


def inspect_images(images):
    reply = subprocess.run([*DOCKER, "image", "inspect", *images], capture_output=True, check=True, timeout=30)
    rows = json.loads(reply.stdout)
    require({row["Id"] for row in rows} == set(images), "INSTALL_BUNDLE_IMAGE_CHANGED")
    return rows


def pack(root, templates_file, output, version, *, image_imports=None, service_images=()):
    root, output = Path(root).resolve(), Path(output)
    require(not output.exists(), "INSTALL_BUNDLE_OUTPUT_EXISTS")
    templates = json.loads(Path(templates_file).read_bytes())
    require(isinstance(templates, list) and 0 < len(templates) <= 16, "INSTALL_BUNDLE_TEMPLATES_INVALID")
    bundle_sha = sha(root / RUNTIME_MANIFEST)
    templates = [{"imageId": image_id(row["imageId"]), "httpImageId": image_id(row["httpImageId"]),
                  "bundleSha256": row["bundleSha256"]} for row in templates]
    require(all(row["bundleSha256"] == bundle_sha for row in templates), "INSTALL_BUNDLE_RUNTIME_CHANGED")
    images = sorted({image_id(value) for row in templates for key, value in row.items() if key.endswith("ImageId") or key == "imageId"}
                    | {image_id(value) for value in service_images})
    inspect_images(images)
    files = runtime_files(root)
    registry = None
    if image_imports:
        registry = json.loads(Path(image_imports).read_bytes())
        require(set(registry) == {"schemaVersion", "images"} and registry["schemaVersion"] == 1
                and isinstance(registry["images"], dict) and set(registry["images"]) <= set(images),
                "INSTALL_BUNDLE_IMPORTS_INVALID")
        require(all(isinstance(modules, list) and len(modules) <= 512
                    and len(set(modules)) == len(modules)
                    and all(isinstance(name, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name)
                            for name in modules) for modules in registry["images"].values()),
                "INSTALL_BUNDLE_IMPORTS_INVALID")
    output.mkdir(mode=0o700)
    for name in files:
        target = output / "runtime" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, target)
    shutil.copyfile(__file__, output / "t4_install_bundle.py")
    if registry:
        (output / "source-image-imports.json").write_text(json.dumps(registry), encoding="utf-8")
    subprocess.run([*DOCKER, "image", "save", "--output", str(output / "images.tar"), *images], check=True, timeout=600)
    inspect_images(images)
    records = {path.relative_to(output).as_posix(): {"bytes": path.stat().st_size, "sha256": sha(path)}
               for path in sorted(output.rglob("*")) if path.is_file()}
    manifest = {"schemaVersion": 1, "version": version, "templates": templates, "imageIds": images,
                "files": records, "sourceDataIncluded": False, "credentialsIncluded": False,
                "publicDistributionReviewed": False}
    (output / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=True, indent=2), encoding="utf-8")
    return {"prepared": True, "manifestSha256": sha(output / MANIFEST), "files": len(records), "images": len(images),
            "bytes": sum(row["bytes"] for row in records.values()), "publicDistributionReviewed": False}


def verify(bundle, expected_sha):
    bundle = Path(bundle).resolve()
    require(re.fullmatch(r"[a-f0-9]{64}", expected_sha) and sha(bundle / MANIFEST) == expected_sha,
            "INSTALL_BUNDLE_MANIFEST_CHANGED")
    value = json.loads((bundle / MANIFEST).read_bytes())
    require(value["schemaVersion"] == 1 and isinstance(value["files"], dict)
            and 0 < len(value["files"]) <= 10000 and value["sourceDataIncluded"] is False
            and value["credentialsIncluded"] is False, "INSTALL_BUNDLE_MANIFEST_INVALID")
    require({"images.tar", "runtime/scripts/install_local_t4.py", "t4_install_bundle.py"} <= set(value["files"]),
            "INSTALL_BUNDLE_MANIFEST_INVALID")
    for name, record in value["files"].items():
        relative(name)
        path = bundle / name
        require(path.is_file() and path.resolve() == path and path.stat().st_size == record["bytes"]
                and sha(path) == record["sha256"], "INSTALL_BUNDLE_FILE_CHANGED")
    return value


def install(bundle, expected_sha, root, unit):
    require(os.name == "posix" and os.geteuid() == 0, "LOCAL_T4_ROOT_REQUIRED")
    root = Path(root)
    runtime = root.with_name(root.name + "-runtime")
    require(root.is_absolute() and root.resolve() == root and not root.exists() and not runtime.exists()
            and re.fullmatch(r"/[A-Za-z0-9_./-]+", str(root))
            and len(str(root / "api/w-123456789abc/executor.sock").encode()) <= 100,
            "LOCAL_T4_INSTALLATION_INVALID")
    require(re.fullmatch(r"at-maintenance-t4-[a-z0-9-]{1,40}", unit)
            and not Path("/etc/systemd/system", unit + ".service").exists(), "LOCAL_T4_SERVICE_ALREADY_EXISTS")
    value = verify(bundle, expected_sha)
    bundle = Path(bundle).resolve()
    require(all(image_id(image) for image in value["imageIds"]), "INSTALL_BUNDLE_IMAGE_INVALID")
    # Everything is verified before Docker import, instance directories or services are changed.
    subprocess.run([*DOCKER, "image", "load", "--input", str(bundle / "images.tar")],
                   check=True, stdout=subprocess.DEVNULL, timeout=600)
    inspect_images(value["imageIds"])
    runtime.mkdir(mode=0o755)
    for name in value["files"]:
        if not name.startswith("runtime/"):
            continue
        target = runtime / name[len("runtime/"):]
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        shutil.copyfile(bundle / name, target)
        target.chmod(0o644)
    subprocess.run(["/usr/bin/python3", "-I", "-c",
        "import sys;sys.path[:0]=[sys.argv[1],sys.argv[2]];import m3u8,Crypto,build_managed_runtime as b;assert b.OUTPUT.read_bytes()==b.body()",
        str(runtime / "vendor"), str(runtime / "scripts")], check=True, timeout=30)
    templates = [{"schemaVersion": 1, "root": str(root), "runtimeRoot": str(runtime),
                  "instanceId": str(uuid.uuid4()), "egressPolicyFile": None, "egressPolicySha256": None, **row}
                 for row in value["templates"]]
    result = load_installer(runtime).install(root, templates, unit)
    if "source-image-imports.json" in value["files"]:
        shutil.copyfile(bundle / "source-image-imports.json", root / "source-image-imports.json")
        (root / "source-image-imports.json").chmod(0o600)
    result.update(unit=unit, runtimeRoot=str(runtime), manifestSha256=expected_sha, importedImages=value["imageIds"])
    with (root / "install-receipt.json").open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=True, indent=2)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    packer = actions.add_parser("pack")
    packer.add_argument("--runtime-root", type=Path, required=True)
    packer.add_argument("--templates-file", type=Path, required=True)
    packer.add_argument("--image-imports-file", type=Path)
    packer.add_argument("--service-image", action="append", default=[])
    packer.add_argument("--output", type=Path, required=True)
    packer.add_argument("--version", required=True)
    installer = actions.add_parser("install")
    installer.add_argument("--bundle", type=Path, required=True)
    installer.add_argument("--expected-sha256", required=True)
    installer.add_argument("--root", type=Path, required=True)
    installer.add_argument("--unit", required=True)
    args = parser.parse_args()
    if args.action == "pack":
        result = pack(args.runtime_root, args.templates_file, args.output, args.version,
                      image_imports=args.image_imports_file, service_images=args.service_image)
    else:
        result = install(args.bundle, args.expected_sha256, args.root, args.unit)
    print(json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
