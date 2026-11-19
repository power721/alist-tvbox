"""Prepare a local, ordered review series. Never publish or change the checkout."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

BASELINE = "7532bb69a1d9399d8b08520da0d794b31aad1a27"
ROOT = Path(__file__).resolve().parents[2]
MODULE = "plugin-maintenance/"
BLOCKED_PARTS = {"target", "node_modules", "dist", "__pycache__", "data", ".git"}
SOURCE_SUFFIXES = {".java", ".py", ".sql", ".json", ".yaml", ".properties",
                   ".vue", ".ts", ".mjs", ".js", ".html", ".ps1", ".md"}
RUNTIME_SCRIPTS = (
    "build_managed_runtime.py", "build_managed_http_context.py", "managed_source_broker.py",
    "managed_broker_service.py", "managed_broker_retirement.py", "managed_runner_tunnel.py", "managed_source_setup.py",
    "managed_origin_extension.py", "managed_local_setup.py", "install_local_t4.py", "t4_install_bundle.py",
    "test_t4_install_bundle.py",
    "requirements-tunnel.txt", "requirements-host.txt",
    "managed_http_acceptance.py", "managed_source_protocol.py",
    "managed_egress_protocol.py", "managed_loader.py", "source_egress.py", "source_origin_discovery.py",
    "maintenance_image.py", "test_maintenance_image.py",
    "build_container_context.py", "build_source_module_context.py", "build_runtime.py", "prepare_plugin_source.py",
    "read_public_python_source.py", "test_read_public_python_source.py",
    "fixture_runtime_runner.py", "container_acceptance_test.py",
    "container_resource_acceptance_test.py", "container_resource_checks.py",
    "container_resource_payload.py",
)
RUNTIME_EXAMPLES = (
    "managed-runner-routes.json", "managed-runner-tunnels.json", "managed-broker-service.json",
)
CONTROL_SCRIPTS = (
    "database_archive.ps1", "use_project_storage.ps1", "instance_archive.py",
    "test_instance_archive.py", "deployment_archive.py", "test_deployment_archive.py", "build_knowledge.py",
    "smoke_test.py", "repair_smoke_test.py", "container_smoke_test.py",
    "release_preparation_smoke_test.py", "source_import_smoke_test.py",
    "upstream_smoke_test.py", "media_probe_smoke_test.py",
    "media_budget_smoke_test.py", "ts_inspection_smoke_test.py",
    "ts_fixture.py", "hls_aes_smoke_test.py", "model_smoke_test.py",
    "model_submission_smoke_test.py", "model_source_budget_smoke_test.py",
    "model_named_budget_smoke_test.py", "model_usage_costs_smoke_test.py",
    "model_cost_budget_smoke_test.py", "at_source_projection.py",
    "freeze_at_profile.py", "verify_at_binding.py", "at_readonly_sample.py",
    "nas_directory_scan.py",
)
REVERSE_ENGINE_FILES = (
    "analysis.mjs", "runner.mjs", "broker.py", "remote_broker.py",
    "package.json", "package-lock.json", "THIRD_PARTY.md",
    "tests/analysis.test.mjs", "tests/runner.test.mjs", "tests/redirect.test.mjs",
    "tests/test_broker.py", "tests/test_remote_broker.py",
)
GROUPS = (
    ("01-isolated-runtime", "Isolated execution and frozen worker resources"),
    ("02-maintenance-control", "Maintenance API, storage, model and release workflow"),
    ("03-maintenance-web", "AT maintenance route and workbench components"),
    ("04-at-embedded", "Opt-in AT host integration and review tooling"),
    ("05-at-native-runtime", "Native AT loader identity and opt-in controlled switching"),
)
NATIVE_HOST_FILES = (
    "src/main/java/cn/har01d/alist_tvbox/service/SubscriptionSourceService.java",
    "src/main/java/cn/har01d/alist_tvbox/service/QqMusicService.java",
    "src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java",
    "src/main/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupService.java",
    "src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java",
    "src/test/java/cn/har01d/alist_tvbox/service/SubscriptionSourceServiceTest.java",
    "src/test/java/cn/har01d/alist_tvbox/service/QqMusicServiceTest.java",
    "src/test/java/cn/har01d/alist_tvbox/service/sync/SyncServiceTest.java",
    "src/test/java/cn/har01d/alist_tvbox/web/T4ControllerTest.java",
    "src/main/java/cn/har01d/alist_tvbox/service/PluginWorkerService.java",
    "src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java",
    "src/main/java/cn/har01d/alist_tvbox/service/PluginService.java",
    "src/main/java/cn/har01d/alist_tvbox/web/T4Controller.java",
    "src/main/resources/scripts/t4worker.py",
)
NATIVE_FILES = (
    *NATIVE_HOST_FILES,
    "src/main/java/cn/har01d/alist_tvbox/service/PluginWorkerAdmission.java",
    "src/main/java/cn/har01d/alist_tvbox/service/PluginMutationAdmission.java",
    "src/main/java/cn/har01d/alist_tvbox/service/PluginMutationGuard.java",
    "src/test/java/cn/har01d/alist_tvbox/service/PluginMutationGuardTestSupport.java",
    "src/test/java/cn/har01d/alist_tvbox/service/PluginWorkerIdentityTest.java",
    "src/test/java/cn/har01d/alist_tvbox/service/SubscriptionT4FilterTest.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/service/EmbeddedAtRuntimeService.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/web/EmbeddedAtRuntimeController.java",
    MODULE + "at-embedded/src/test/java/cn/har01d/alist_tvbox/maintenance/EmbeddedMaintenanceIntegrationTest.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/dto/EmbeddedDeploymentSummary.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/dto/EmbeddedDeploymentTarget.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/dto/RollbackEmbeddedDeploymentRequest.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/dto/StartEmbeddedDeploymentRequest.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/entity/EmbeddedDeployment.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/repository/EmbeddedDeploymentRepository.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/service/EmbeddedDeploymentService.java",
    MODULE + "at-embedded/src/main/java/cn/har01d/atmaintenance/web/EmbeddedDeploymentController.java",
    MODULE + "at-embedded/src/main/resources/db/migration/h2/V60__MaintenanceAtDeployment.sql",
    MODULE + "at-embedded/src/main/resources/db/migration/h2/V65__RuntimeActivation.sql",
    MODULE + "at-embedded/src/test/java/cn/har01d/alist_tvbox/maintenance/EmbeddedDeploymentIntegrationTest.java",
    "docs/plugin-maintenance/at-native-runtime.md",
    "docs/plugin-maintenance/at-native-deployment.md",
)
DOCS = (
    "docs/plugin-maintenance/github-review-2026-11-19.md",
    "docs/plugin-maintenance/merge-guide.md",
    "docs/plugin-maintenance/embedded-integration.md",
    "docs/plugin-maintenance/build-and-acceptance.md",
    "docs/plugin-maintenance/getting-started.md",
    "docs/plugin-maintenance/upstream-review.md",
    "docs/plugin-maintenance/candidate-transfer.md",
    "docs/plugin-maintenance/database-archive.md",
    "docs/plugin-maintenance/instance-archive.md",
    "docs/plugin-maintenance/deployment-archive.md",
    "docs/plugin-maintenance/managed-runner-operations.md",
    "docs/plugin-maintenance/crawler-repair-recipes.md",
    "docs/plugin-maintenance/spider-delivery-standard.md",
    "docs/plugin-maintenance/source-companion-modules.md",
    "docs/plugin-maintenance/reverse-engine.md",
    "docs/plugin-maintenance/image-lifecycle.md",
    "docs/plugin-maintenance/t4-home-current-runtime-2026-11-16.md",
    "docs/plugin-maintenance/configured-source-origins-2026-11-16.md",
)


def sha(body):
    return hashlib.sha256(body).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode()


def git(root, *args, input=None):
    env = os.environ.copy()
    # Do not inherit a caller's alternate index/worktree into either scratch repo.
    for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES"):
        env.pop(name, None)
    result = subprocess.run(
        ["git", "-c", "core.autocrlf=false", "-c", "core.safecrlf=false",
         "-C", str(root), *args], input=input, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, env=env, timeout=90, check=False)
    if result.returncode:
        raise ValueError("REVIEW_GIT_FAILED: " + " ".join(args[:2]))
    return result.stdout


def read_source(root, name):
    path = root / name
    if any(part in BLOCKED_PARTS for part in Path(name).parts):
        raise ValueError("REVIEW_PRIVATE_PATH: " + name)
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError("REVIEW_SYMLINK: " + name)
    return path.read_bytes()


def subtree(root, prefix):
    return sorted(path.relative_to(root).as_posix()
                  for path in (root / prefix).rglob("*")
                  if path.is_file()
                  and not any(part in BLOCKED_PARTS for part in path.relative_to(root).parts)
                  and path.suffix in SOURCE_SUFFIXES)


def select_sources(root):
    scripts = MODULE + "scripts/"
    runtime = set(subtree(root, MODULE + "server/src/main/resources/worker"))
    for directory in ("managed-source", "fixtures", "container", "http-acceptance"):
        runtime.update(subtree(root, MODULE + directory))
    runtime.update(MODULE + name for name in (".gitignore", ".gitattributes", "container/Dockerfile",
                   "container/Dockerfile.source-dependencies", "container/Dockerfile.source-modules",
                   "container/Dockerfile.source-input-dispatch"))
    runtime.update(scripts + name for name in RUNTIME_SCRIPTS)
    runtime.update(MODULE + "examples/" + name for name in RUNTIME_EXAMPLES)
    runtime.update(scripts + path.name for path in (root / scripts).glob("test_*.py")
                   if path.name.startswith(("test_managed_", "test_source_", "test_container_"))
                   or path.name in {"test_static_inspector.py", "test_egress_urllib.py", "test_egress_input_dispatch.py",
                                    "test_prepare_plugin_source.py", "test_at_runtime_fixture.py",
                                    "test_build_managed_http_context.py", "test_t4_home_runtime.py"})
    runtime.add(MODULE + "at-contract-lab/fixtures/t4_home.py")
    control = set(subtree(root, MODULE + "server/src")) - runtime
    control.add(MODULE + "server/pom.xml")
    control.update(scripts + name for name in CONTROL_SCRIPTS)
    control.update(MODULE + "reverse-engine/" + name for name in REVERSE_ENGINE_FILES)
    control.update(scripts + name for name in
                   ("test_build_knowledge.py", "test_verify_at_binding.py", "test_nas_directory_scan.py"))
    web = set(subtree(root, "web-ui/src/maintenance"))
    web.update(subtree(root, "web-ui/tests/maintenance"))
    web.add("web-ui/package.json")
    embedded = set(subtree(root, MODULE + "at-embedded/src"))
    embedded.update((".gitignore", "pom.xml", MODULE + "README.md",
                     "web-ui/src/App.vue", "web-ui/src/router/index.ts", "web-ui/src/views/MaintenanceView.vue",
                     "src/main/resources/META-INF/native-image/resource-config.json", *DOCS))
    embedded.update(scripts + name for name in
                    ("prepare_upstream_review.py", "test_prepare_upstream_review.py"))
    native = set(NATIVE_FILES)
    embedded.difference_update(native)
    selected = [runtime, control, web, embedded, native]
    seen = set()
    for files in selected:
        if seen.intersection(files):
            raise ValueError("REVIEW_DUPLICATE_OWNER")
        seen.update(files)
    return [dict((name, read_source(root, name)) for name in sorted(files)) for files in selected]


def normalized_xml(body, remove_profile=False):
    node = ET.fromstring(body)
    ns = "{http://maven.apache.org/POM/4.0.0}"
    if remove_profile:
        profiles = node.find(ns + "profiles")
        matches = [p for p in profiles if p.findtext(ns + "id") == "maintenance-embedded"]
        if len(matches) != 1:
            raise ValueError("REVIEW_EMBEDDED_PROFILE_MISSING")
        profiles.remove(matches[0])
        http = [p for p in profiles if p.findtext(ns + "id") == "maintenance-http-acceptance"]
        if len(http) > 1:
            raise ValueError("REVIEW_HTTP_PROFILE_DUPLICATED")
        for profile in http:
            profiles.remove(profile)
    for element in node.iter():
        if element.text is not None and not element.text.strip():
            element.text = None
        if element.tail is not None and not element.tail.strip():
            element.tail = None
    return ET.tostring(node, encoding="utf-8")


def baseline_files(root, baseline, payloads):
    if git(root, "rev-parse", "HEAD").decode().strip() != baseline:
        raise ValueError("REVIEW_BASELINE_CHANGED")
    result = {name: git(root, "show", baseline + ":" + name)
              for name in (".gitignore", "pom.xml", *NATIVE_HOST_FILES)}
    if normalized_xml(result["pom.xml"]) != normalized_xml(payloads["pom.xml"], True):
        raise ValueError("REVIEW_UNRELATED_HOST_POM_CHANGE")
    expected_ignore = result[".gitignore"].decode().splitlines() + [
        "/data/connections.json", "/data/maintenance-connections.json"]
    if payloads[".gitignore"].decode().splitlines() != expected_ignore:
        raise ValueError("REVIEW_UNRELATED_HOST_IGNORE_CHANGE")
    tracked = git(root, "ls-tree", "-r", "--name-only", baseline, "--", MODULE).splitlines()
    if tracked:
        raise ValueError("REVIEW_MODULE_ALREADY_TRACKED")
    return result


def put_files(root, files):
    for name, body in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)


def stage(root, files):
    git(root, "add", "-f", "--pathspec-from-file=-", "--pathspec-file-nul",
        input=b"".join(name.encode() + b"\0" for name in sorted(files)))


def create_series(base, groups):
    patches = []
    with tempfile.TemporaryDirectory(prefix="at-review-build-") as temp:
        root = Path(temp)
        git(root, "init", "-q")
        put_files(root, base)
        stage(root, base)
        previous = git(root, "write-tree").decode().strip()
        for files in groups:
            put_files(root, files)
            stage(root, files)
            patches.append(git(root, "diff", "--cached", "--binary", "--full-index",
                               "--no-renames", previous, "--", "."))
            previous = git(root, "write-tree").decode().strip()
    return patches


def verify_series(base, groups, patches):
    with tempfile.TemporaryDirectory(prefix="at-review-verify-") as temp:
        root = Path(temp)
        git(root, "init", "-q")
        put_files(root, base)
        stage(root, base)
        count = 0
        for files, patch in zip(groups, patches, strict=True):
            git(root, "apply", "--index", "--whitespace=nowarn", "-", input=patch)
            for name, body in files.items():
                if (root / name).read_bytes() != body:
                    raise ValueError("REVIEW_APPLIED_BYTES_CHANGED: " + name)
            count += len(files)
        expected_files = dict(base)
        for files in groups:
            expected_files.update(files)
        actual = set(git(root, "ls-files", "-z").decode().split("\0")) - {""}
        if actual != set(expected_files):
            raise ValueError("REVIEW_APPLIED_PATHS_CHANGED")
        for name, body in expected_files.items():
            if (root / name).read_bytes() != body:
                raise ValueError("REVIEW_FINAL_BYTES_CHANGED: " + name)
    return {"patchesApplied": len(patches), "fileBytesVerified": count,
            "separateScratchRepository": True, "businessTestsRepeated": False}


def build(root, baseline=BASELINE):
    groups = select_sources(root)
    all_files = {name: body for files in groups for name, body in files.items()}
    base = baseline_files(root, baseline, all_files)
    patches = create_series(base, groups)
    verification = verify_series(base, groups, patches)
    artifacts = {}
    series = []
    for index, ((name, title), files, patch) in enumerate(zip(GROUPS, groups, patches, strict=True)):
        path = name + ".patch"
        artifacts[path] = patch
        series.append({"id": name, "title": title,
                       "dependsOn": [GROUPS[index - 1][0]] if index else [],
                       "patch": path, "patchSha256": sha(patch),
                       "files": {name: {"sha256": sha(body), "bytes": len(body)}
                                 for name, body in files.items()}})
    manifest = {
        "schemaVersion": 1, "baselineCommit": baseline,
        "authority": "local_ordered_upstream_review_candidate",
        "productionModified": False, "published": False, "pullRequestsCreated": False,
        "modules": series, "verification": verification,
        "baselineFiles": {name: {"sha256": sha(body), "bytes": len(body)}
                          for name, body in base.items()},
        "excluded": ["private data, model keys, connections, recovery receipts and logs",
                     "build outputs, dependency caches and node_modules",
                     "historical AT laboratory candidates and private NAS adapters"],
        "remaining": ["upstream review and actual PR publication",
                      "whole-host reboot acceptance and production recovery adaptation",
                      "production publish/recovery adaptation and long-running trial",
                      "MySQL/PostgreSQL and Native Image validation"],
    }
    artifacts["manifest.json"] = json_bytes(manifest)
    artifacts["series"] = ("\n".join(row["patch"] for row in series) + "\n").encode()
    artifacts["README.md"] = (
        "# Local Upstream Review Candidate\n\n"
        "Baseline: `" + baseline + "`.\n\n"
        "Apply patches in `series` order to a clean checkout of that baseline using "
        "`git apply --index <patch>`. Each patch depends on the preceding patch; "
        "these are not independently buildable products.\n\n"
        "Review scopes: isolated executor, maintenance control plane, AT workbench components, "
        "then opt-in AT host wiring, native loader observation and controlled switching. "
        "The last unit includes the explicitly listed native host source changes. "
        "Build the AT Web UI before packaging with maintenance-embedded. Follow the included "
        "`docs/plugin-maintenance/upstream-review.md` for commands and limits.\n\n"
        "Only patch application and exact source bytes were checked in this preparation. "
        "Existing runtime receipts remain bound to their original JARs and owners. "
        "This archive does not publish anything, migrate a production database, "
        "prove all source compatibility, or include private acceptance adapters.\n"
    ).encode()
    return artifacts, manifest


def append(root, parent_archive, parent_source, expected_parent_sha256):
    parent_body = parent_archive.read_bytes()
    if sha(parent_body) != expected_parent_sha256:
        raise ValueError("REVIEW_PARENT_ARCHIVE_CHANGED")
    with zipfile.ZipFile(parent_archive) as package:
        old = json.loads(package.read("manifest.json"))
        artifacts = {row["patch"]: package.read(row["patch"]) for row in old["modules"]}
    if not old["modules"] or any(sha(artifacts[row["patch"]]) != row["patchSha256"] for row in old["modules"]):
        raise ValueError("REVIEW_PARENT_PATCH_CHANGED")
    records = {name: row for group in old["modules"] for name, row in group["files"].items()}
    groups = select_sources(root)
    current = {name: body for group in groups for name, body in group.items()}
    if records.keys() - current.keys():
        raise ValueError("REVIEW_REMOVED_PATH_REQUIRES_EXPLICIT_HANDLING")
    base = {name: read_source(parent_source, name) for name in records}
    if any({"sha256": sha(body), "bytes": len(body)} != records[name] for name, body in base.items()):
        raise ValueError("REVIEW_PARENT_SOURCE_CHANGED")
    changed = {name for name, body in current.items() if body != base.get(name)}
    if not changed:
        raise ValueError("REVIEW_NO_NEW_DELIVERY_INPUTS")
    delta = [(owner, {name: body for name, body in group.items() if name in changed})
             for owner, group in enumerate(groups)]
    delta = [(owner, files) for owner, files in delta if files]
    patches = create_series(base, [files for _, files in delta])
    verification = verify_series(base, [files for _, files in delta], patches)
    version = ET.fromstring(current[MODULE + "server/pom.xml"]).findtext(
        "m:version", namespaces={"m": "http://maven.apache.org/POM/4.0.0"})
    if not version or any(char not in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.-" for char in version):
        raise ValueError("REVIEW_INVALID_PLATFORM_VERSION")
    manifest = copy.deepcopy(old)
    previous = old["modules"][-1]["id"]
    for number, ((owner, files), patch) in enumerate(zip(delta, patches, strict=True), len(old["modules"]) + 1):
        name = f"{number:02d}-" + GROUPS[owner][0][3:] + "-updates-" + version
        path = name + ".patch"
        artifacts[path] = patch
        manifest["modules"].append({"id": name, "title": GROUPS[owner][1] + " incremental update",
            "dependsOn": [previous], "patch": path, "patchSha256": sha(patch),
            "files": {name: {"sha256": sha(body), "bytes": len(body)} for name, body in files.items()}})
        previous = name
    manifest.update(parentArchiveSha256=expected_parent_sha256, originalPatchCount=len(old["modules"]),
        deltaPatchCount=len(patches), platformVersion=version, currentSourceFileCount=len(current),
        changedSourceFiles=sorted(changed), addedSourceFiles=sorted(current.keys() - base.keys()),
        verification={**verification, "finalSourceFiles": len(current),
            "originalPatchesBytePreserved": True, "parentSourceFilesShaVerified": len(base)})
    artifacts["manifest.json"] = json_bytes(manifest)
    artifacts["series"] = ("\n".join(row["patch"] for row in manifest["modules"]) + "\n").encode()
    artifacts["README.md"] = (
        "# Local Review Series " + version + "\n\nBaseline: `" + manifest["baselineCommit"] + "`.\n\n"
        "The first " + str(len(old["modules"])) + " patches preserve parent archive `"
        + expected_parent_sha256 + "` byte for byte. Apply the complete series to the clean baseline. "
        "Apply only the appended patches when starting from the exact parent source identity. "
        "Review units are ordered and dependent, not independently buildable products.\n\n"
        "Preparation verifies patch application and exact source bytes, not builds or runtime health. "
        "Existing acceptance receipts remain bound to their original artifacts and owners. "
        "No private connections, credentials, databases, source samples or acceptance receipts are included. "
        "This archive does not publish, create a PR, switch services or enable scheduling.\n"
    ).encode()
    return artifacts, manifest


def write_archive(path, files):
    with path.open("xb") as output:
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, body in sorted(files.items()):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, body, compress_type=zipfile.ZIP_DEFLATED)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New local ZIP; never overwritten")
    parser.add_argument("--parent-archive", type=Path, help="Accepted ZIP whose patches must remain unchanged")
    parser.add_argument("--parent-source", type=Path, help="Exact final source checkout of the parent archive")
    parser.add_argument("--parent-sha256", help="Trusted SHA-256 of the parent archive")
    args = parser.parse_args()
    parent = (args.parent_archive, args.parent_source, args.parent_sha256)
    if any(parent) and not all(parent):
        parser.error("--parent-archive, --parent-source and --parent-sha256 must be supplied together")
    artifacts, manifest = append(ROOT, *parent) if all(parent) else build(ROOT)
    write_archive(args.output, artifacts)
    print(json.dumps({"archive": str(args.output.resolve()),
                      "sha256": sha(args.output.read_bytes()),
                      **manifest["verification"], "published": False}))


if __name__ == "__main__":
    main()
