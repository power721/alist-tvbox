"""Freeze a local standalone source candidate; never publish or touch the active checkout."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

import prepare_upstream_review as review

ROOT = Path(__file__).resolve().parents[2]
EXTRA = (
    "plugin-maintenance/deployment/Dockerfile.standalone",
    "plugin-maintenance/deployment/Dockerfile.embedded",
    "plugin-maintenance/deployment/service_entry.py",
    "plugin-maintenance/deployment/compose.yaml",
    "plugin-maintenance/deployment/compose.t4.yaml",
    "plugin-maintenance/scripts/build_service_context.py",
    "plugin-maintenance/scripts/test_build_service_context.py",
    "plugin-maintenance/container/requirements.txt",
    "plugin-maintenance/scripts/prepare_upstream_review.py",
    "plugin-maintenance/scripts/prepare_standalone_release.py",
    "plugin-maintenance/scripts/test_prepare_standalone_release.py",
    "plugin-maintenance/scripts/standalone_install_smoke.py",
    "docs/plugin-maintenance/standalone-release.md",
    "docs/plugin-maintenance/dual-mode-release.md",
    "docs/plugin-maintenance/standalone-notice.md",
)


def validate_files(files):
    for name, body in files.items():
        parts = Path(name).parts
        if (Path(name).is_absolute() or ".." in parts or "\\" in name
                or any(part in review.BLOCKED_PARTS | {".codex", ".claude", "recovery"} for part in parts)
                or Path(name).name in {"connections.json", "runner-routes.json", "runner-tunnels.json"}
                or not (name.startswith("plugin-maintenance/") or name in EXTRA
                        or name in {"README.md", "NOTICE.md", ".gitignore"})):
            raise ValueError("STANDALONE_PRIVATE_OR_UNEXPECTED_PATH: " + name)
        if re.search(rb"sk-[A-Za-z0-9]{24,}|-----BEGIN [A-Z ]*PRIVATE KEY-----", body):
            raise ValueError("STANDALONE_SECRET_LIKE_CONTENT: " + name)


def collect(root):
    files = {name: body for group in review.select_sources(root, standalone=True) for name, body in group.items()}
    files.update({name: review.read_source(root, name) for name in EXTRA})
    files["README.md"] = files["docs/plugin-maintenance/standalone-release.md"]
    files["NOTICE.md"] = files["docs/plugin-maintenance/standalone-notice.md"]
    files[".gitignore"] = b".codex/\n**/target/\n**/node_modules/\n**/dist/\n**/__pycache__/\n**/data/\n.env\n"
    validate_files(files)
    return files


def payload(root, timestamp):
    datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    files = collect(root)
    version = ET.fromstring(files["plugin-maintenance/server/pom.xml"]).findtext(
        "m:version", namespaces={"m": "http://maven.apache.org/POM/4.0.0"})
    if not version or not re.fullmatch(r"[A-Za-z0-9.-]+", version):
        raise ValueError("STANDALONE_INVALID_VERSION")
    records = {name: {"bytes": len(body), "sha256": review.sha(body)} for name, body in sorted(files.items())}
    manifest = {"schemaVersion": 1, "deployment": "standalone-jar", "version": version,
                "buildTimestamp": timestamp, "inputsSha256": review.sha(review.json_bytes(records)),
                "files": records, "publicDistributionReviewed": False,
                "exclusions": ["AT host", "embedded integration", "private connections", "databases",
                               "site samples", "recovery receipts", "generated output", "dependency caches"]}
    return {**files, "release-manifest.json": review.json_bytes(manifest)}, manifest


def restore_archive(archive, output):
    with zipfile.ZipFile(archive) as package:
        manifest = json.loads(package.read("release-manifest.json"))
        files = {name: package.read(name) for name in manifest["files"]}
        if set(package.namelist()) != set(files) | {"release-manifest.json"}:
            raise ValueError("STANDALONE_ARCHIVE_FILES_CHANGED")
        validate_files(files)
        records = {name: {"bytes": len(body), "sha256": review.sha(body)} for name, body in sorted(files.items())}
        if records != manifest["files"] or review.sha(review.json_bytes(records)) != manifest["inputsSha256"]:
            raise ValueError("STANDALONE_ARCHIVE_BYTES_CHANGED")
        output.mkdir(exist_ok=False)
        review.put_files(output, {**files, "release-manifest.json": package.read("release-manifest.json")})
    return len(files)


def freeze(root, output, timestamp):
    artifacts, manifest = payload(root, timestamp)
    output.mkdir(parents=False, exist_ok=False)
    archive = output / ("at-plugin-maintenance-" + manifest["version"] + "-source.zip")
    review.write_archive(archive, artifacts)
    restored = restore_archive(archive, output / "build-source")
    baseline = output / "source-baseline"
    baseline.mkdir()
    review.put_files(baseline, artifacts)
    review.git(baseline, "init", "-q", "-b", "codex/standalone-release")
    review.stage(baseline, artifacts)
    review.git(baseline, "-c", "user.name=Maintenance Release", "-c", "user.email=maintenance@localhost",
               "commit", "-q", "-m", "Freeze standalone candidate " + manifest["version"])
    receipt = {"version": manifest["version"], "sourceFiles": len(manifest["files"]),
               "sourceBytes": sum(row["bytes"] for row in manifest["files"].values()),
               "archive": archive.name, "archiveBytes": archive.stat().st_size,
               "archiveSha256": review.sha(archive.read_bytes()), "inputsSha256": manifest["inputsSha256"],
               "commit": review.git(baseline, "rev-parse", "HEAD").decode().strip(),
               "tree": review.git(baseline, "rev-parse", "HEAD^{tree}").decode().strip(),
               "restoredFilesVerified": restored, "buildSource": "build-source",
               "remoteConfigured": False, "published": False, "publicDistributionReviewed": False}
    (output / "source-receipt.json").write_bytes(review.json_bytes(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="A new directory under the project's release storage")
    parser.add_argument("--build-timestamp", required=True, help="Fixed ISO timestamp passed to Maven for this candidate")
    args = parser.parse_args()
    print(json.dumps(freeze(ROOT, args.output.resolve(), args.build_timestamp), ensure_ascii=True))


if __name__ == "__main__":
    main()
