"""Export an allowlisted Docker build context; does not build, pull or run an image."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import zipfile

MODULE = Path(__file__).resolve().parents[1]
WORKER = MODULE / "server/src/main/resources/worker"
RUNTIME_FILES = {"Atvp.py", "bootstrap.py", "demo.py", "probe_fixture.py", "t4cenc.py", "t4worker.py"}


def container_bundle():
    scripts = {name: (WORKER / name).read_bytes()
               for name in ("container_supervisor.py", "container_entry.py")}
    tree = ast.parse(scripts["container_supervisor.py"])
    assignments = {target.id: node.value for node in tree.body if isinstance(node, ast.Assign)
                   for target in node.targets if isinstance(target, ast.Name)}
    policy_node = assignments["POLICY"]
    for index, value in enumerate(policy_node.values):
        if isinstance(value, ast.Name) and value.id == "TMPFS":
            policy_node.values[index] = ast.Constant(ast.literal_eval(assignments["TMPFS"]))
    policy = ast.literal_eval(policy_node)
    return {
        "schemaVersion": 1, "mode": "container_fixture", "authority": "expected_bundle",
        "runtimeSha256": hashlib.sha256((WORKER / "runtime/manifest.json").read_bytes()).hexdigest(),
        "policy": policy,
        "files": {name: {"sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}
                  for name, body in scripts.items()},
        "boundary": "Expected trusted-fixture container protocol only; no engine, image or Linux runtime verified.",
    }


def prepare(base_image):
    if not re.fullmatch(r"[A-Za-z0-9/._:-]+@sha256:[a-f0-9]{64}", base_image):
        raise ValueError("A base image pinned by repository@sha256 digest is required")
    if json.loads((WORKER / "container-manifest.json").read_bytes()) != container_bundle():
        raise ValueError("Container bundle manifest is stale")
    files = {"Dockerfile": (MODULE / "container/Dockerfile").read_bytes(),
             "requirements.txt": (MODULE / "container/requirements.txt").read_bytes(),
             "container_entry.py": (WORKER / "container_entry.py").read_bytes(),
             "runtime/manifest.json": (WORKER / "runtime/manifest.json").read_bytes()}
    manifest = json.loads(files["runtime/manifest.json"])
    if set(manifest["files"]) != RUNTIME_FILES:
        raise ValueError("Runtime file allowlist mismatch")
    for name, record in manifest["files"].items():
        body = (WORKER / "runtime" / name).read_bytes()
        if len(body) != record["bytes"] or hashlib.sha256(body).hexdigest() != record["sha256"]:
            raise ValueError("Runtime bundle identity mismatch")
        files["runtime/" + name] = body
    context = {
        "schemaVersion": 1, "authority": "expected_build_context", "baseImage": base_image,
        "buildArgs": {"PYTHON_IMAGE": base_image,
                      "RUNTIME_SHA256": hashlib.sha256(files["runtime/manifest.json"]).hexdigest(),
                      "ENTRY_SHA256": hashlib.sha256(files["container_entry.py"]).hexdigest()},
        "files": {name: {"sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}
                  for name, body in files.items()},
        "boundary": "Source context only; image build may download pinned dependencies. "
                    "No image existence, Linux compatibility, resource enforcement or sandbox verdict.",
    }
    return files, context


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-image")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--refresh-bundle", action="store_true")
    parser.add_argument("--check-bundle", action="store_true")
    args = parser.parse_args()
    if args.refresh_bundle or args.check_bundle:
        if args.refresh_bundle and args.check_bundle or args.base_image or args.output:
            parser.error("Bundle refresh/check cannot be combined with context export")
        path = WORKER / "container-manifest.json"
        body = (json.dumps(container_bundle(), separators=(",", ":")) + "\n").encode("utf-8")
        if args.refresh_bundle:
            path.write_bytes(body)
        elif path.read_bytes() != body:
            raise ValueError("Container bundle manifest is stale")
        print(json.dumps({"containerBundleSha256": hashlib.sha256(body).hexdigest(),
                          "mode": "check" if args.check_bundle else "refresh"}))
        return
    if not args.base_image or args.output is None:
        parser.error("--base-image and --output are required for context export")
    files, manifest = prepare(args.base_image)
    # Refuse to replace an existing artifact silently.
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, body in files.items():
            archive.writestr(name, body)
        archive.writestr("build-context-manifest.json", json.dumps(manifest, indent=2).encode("utf-8"))
    print(json.dumps({"output": str(args.output), "files": len(files),
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      "buildArgs": manifest["buildArgs"]}, indent=2))


if __name__ == "__main__":
    main()
