"""Focused packaging checks, without model calls, Maven or source execution."""
import ast
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare_upstream_review as R


class UpstreamReviewTests(unittest.TestCase):
    def test_offline_deployment_tools_and_instructions_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        selected = {name for group in groups for name in group}
        self.assertTrue({
            "plugin-maintenance/scripts/instance_archive.py",
            "plugin-maintenance/scripts/deployment_archive.py",
            "plugin-maintenance/scripts/test_deployment_archive.py",
            "docs/plugin-maintenance/instance-archive.md",
            "docs/plugin-maintenance/deployment-archive.md",
        } <= selected)
        self.assertIn(b"deployment-archive.md", groups[3]["docs/plugin-maintenance/instance-archive.md"])

    def test_configured_origin_setup_parser_and_instructions_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        self.assertTrue({
            "plugin-maintenance/scripts/managed_source_setup.py",
            "plugin-maintenance/scripts/managed_loader.py",
            "plugin-maintenance/scripts/source_origin_discovery.py",
            "plugin-maintenance/scripts/test_source_origin_discovery.py",
        } <= groups[0].keys())
        self.assertIn("plugin-maintenance/server/src/main/java/cn/har01d/atmaintenance/service/ManagedSourceSetup.java",
                      groups[1])
        self.assertIn("docs/plugin-maintenance/configured-source-origins-2026-11-16.md", groups[3])
        self.assertIn(b"sourceConfig", groups[3]["docs/plugin-maintenance/managed-runner-operations.md"])

    def parent_fixture(self, directory):
        source = directory / "parent-source"
        files = {"plugin-maintenance/server/pom.xml": (
            b'<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.74.1-SNAPSHOT</version></project>'),
            "plugin-maintenance/fixture.py": b"# original\r\n"}
        R.put_files(source, files)
        patch_body = R.create_series({}, [files])[0]
        manifest = {"schemaVersion": 1, "baselineCommit": "fixture", "baselineFiles": {},
            "published": False, "pullRequestsCreated": False, "productionModified": False,
            "modules": [{"id": "01-parent", "patch": "01-parent.patch", "patchSha256": R.sha(patch_body),
                "files": {name: {"sha256": R.sha(body), "bytes": len(body)} for name, body in files.items()}}]}
        archive = directory / "parent.zip"
        R.write_archive(archive, {"manifest.json": R.json_bytes(manifest), "01-parent.patch": patch_body})
        return source, archive, files, patch_body

    def test_append_preserves_parent_patches_and_exact_delta_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, archive, base, original_patch = self.parent_fixture(root)
            changed = {**base, "plugin-maintenance/fixture.py": b"# updated\r\n",
                       "plugin-maintenance/added.py": b"# new\n"}
            with patch.object(R, "select_sources", return_value=[{}, changed, {}, {}, {}]):
                artifacts, manifest = R.append(root, archive, source, R.sha(archive.read_bytes()))
            self.assertEqual(original_patch, artifacts["01-parent.patch"])
            self.assertEqual(2, len(manifest["modules"]))
            self.assertEqual(["01-parent"], manifest["modules"][1]["dependsOn"])
            self.assertEqual(["plugin-maintenance/added.py"], manifest["addedSourceFiles"])
            self.assertEqual("0.74.1-SNAPSHOT", manifest["platformVersion"])
            self.assertEqual(3, manifest["verification"]["finalSourceFiles"])
            self.assertFalse(manifest["published"])
            self.assertFalse(manifest["pullRequestsCreated"])
            self.assertFalse(manifest["productionModified"])

    def test_append_rejects_changed_parent_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, archive, _, _ = self.parent_fixture(root)
            with self.assertRaisesRegex(ValueError, "REVIEW_PARENT_ARCHIVE_CHANGED"):
                R.append(root, archive, source, "0" * 64)

    def test_append_rejects_changed_parent_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, archive, base, _ = self.parent_fixture(root)
            R.put_files(source, {"plugin-maintenance/fixture.py": b"# unexpected\n"})
            with patch.object(R, "select_sources", return_value=[{}, base, {}, {}, {}]):
                with self.assertRaisesRegex(ValueError, "REVIEW_PARENT_SOURCE_CHANGED"):
                    R.append(root, archive, source, R.sha(archive.read_bytes()))

    def test_append_rejects_removed_or_unchanged_sources(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, archive, base, _ = self.parent_fixture(root)
            for current, error in (({}, "REVIEW_REMOVED_PATH_REQUIRES_EXPLICIT_HANDLING"),
                                   (base, "REVIEW_NO_NEW_DELIVERY_INPUTS")):
                with self.subTest(error=error), patch.object(R, "select_sources", return_value=[{}, current, {}, {}, {}]):
                    with self.assertRaisesRegex(ValueError, error):
                        R.append(root, archive, source, R.sha(archive.read_bytes()))

    def test_windows_entrypoints_deliver_their_project_storage_helper(self):
        groups = R.select_sources(R.ROOT)
        helper = "plugin-maintenance/scripts/use_project_storage.ps1"
        self.assertIn(helper, groups[1])
        for name in ("start_local.ps1", "database_archive.ps1"):
            body = groups[1]["plugin-maintenance/scripts/" + name].decode("utf-8")
            self.assertIn("'use_project_storage.ps1'", body)
            self.assertIn('"-Djava.io.tmpdir=$env:TEMP"', body)

    def test_bounded_retirement_selector_is_delivered_with_its_checks(self):
        groups = R.select_sources(R.ROOT)
        self.assertIn("plugin-maintenance/scripts/managed_broker_retirement.py", groups[0])
        self.assertIn("plugin-maintenance/scripts/test_managed_broker_retirement.py", groups[0])

    def test_home_increment_sources_and_regression_fixture_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        self.assertIn("plugin-maintenance/scripts/test_t4_home_runtime.py", groups[0])
        self.assertIn("plugin-maintenance/at-contract-lab/fixtures/t4_home.py", groups[0])
        self.assertIn("plugin-maintenance/managed-source/egress_payload.py", groups[0])
        self.assertIn("src/main/resources/scripts/t4worker.py", groups[4])
        self.assertIn("docs/plugin-maintenance/t4-home-current-runtime-2026-11-16.md", groups[3])
        self.assertIn("plugin-maintenance/server/src/test/java/cn/har01d/atmaintenance/service/ManagedSourceControlFrameTest.java", groups[1])

    def test_image_lifecycle_entrypoint_checks_and_policy_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        self.assertIn("plugin-maintenance/scripts/maintenance_image.py", groups[0])
        self.assertIn("plugin-maintenance/scripts/test_maintenance_image.py", groups[0])
        self.assertIn("docs/plugin-maintenance/image-lifecycle.md", groups[3])

    def test_current_credential_observation_api_and_redirect_classification_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        prefix = "plugin-maintenance/server/src/main/java/cn/har01d/atmaintenance/"
        self.assertIn(prefix + "dto/ObserveSourceCredentialsRequest.java", groups[1])
        for suffix in ("service/SourceCredentialStore.java", "service/RepairReport.java"):
            self.assertIn(prefix + suffix, groups[1])

    def test_reverse_worker_and_offline_checks_are_delivered_without_private_smoke(self):
        groups = R.select_sources(R.ROOT)
        prefix = "plugin-maintenance/reverse-engine/"
        expected = {prefix + name for name in (
            "analysis.mjs", "runner.mjs", "broker.py", "remote_broker.py",
            "package.json", "package-lock.json", "THIRD_PARTY.md",
            "tests/analysis.test.mjs", "tests/runner.test.mjs", "tests/redirect.test.mjs",
            "tests/test_broker.py", "tests/test_remote_broker.py",
        )}
        selected = {name for group in groups for name in group}
        self.assertEqual(expected, {name for name in selected if name.startswith(prefix)})
        self.assertTrue(expected <= groups[1].keys())
        self.assertIn("docs/plugin-maintenance/reverse-engine.md", groups[3])
        package = json.loads(groups[1][prefix + "package.json"])
        lock = json.loads(groups[1][prefix + "package-lock.json"])
        self.assertEqual({"chrome-remote-interface": "0.34.0", "playwright-core": "1.58.2",
                          "webcrack": "2.15.1"}, package["dependencies"])
        self.assertEqual(package["dependencies"], lock["packages"][""]["dependencies"])
        for name, version in package["dependencies"].items():
            self.assertEqual(version, lock["packages"]["node_modules/" + name]["version"])
        self.assertIn(b"npm ci --ignore-scripts", groups[1][prefix + "THIRD_PARTY.md"])

    def test_companion_module_tools_and_image_layers_are_delivered(self):
        groups = R.select_sources(R.ROOT)
        self.assertTrue({
            'plugin-maintenance/scripts/build_source_module_context.py',
            'plugin-maintenance/scripts/test_source_module_context.py',
            'plugin-maintenance/container/Dockerfile.source-dependencies',
            'plugin-maintenance/container/Dockerfile.source-modules',
            'plugin-maintenance/container/Dockerfile.source-input-dispatch',
            'plugin-maintenance/scripts/test_egress_input_dispatch.py',
        } <= groups[0].keys())
        self.assertIn('docs/plugin-maintenance/source-companion-modules.md', groups[3])

    def test_series_applies_exact_bytes_in_dependency_order(self):
        base = {"pom.xml": b"<project/>\r\n", ".gitignore": b"target/\n"}
        groups = [
            {"plugin-maintenance/worker.py": b"print('fixture')\n"},
            {"plugin-maintenance/server.json": b'{"value":1}'},
            {"plugin-maintenance/web.html": b"<p>fixture</p>\r\n"},
            {"pom.xml": b"<project><profiles/></project>\r\n"},
            {"src/main/resources/scripts/t4worker.py": b"# native fixture\n"},
        ]
        patches = R.create_series(base, groups)
        result = R.verify_series(base, groups, patches)
        self.assertEqual(5, result["patchesApplied"])
        self.assertEqual(5, result["fileBytesVerified"])
        self.assertFalse(result["businessTestsRepeated"])
        with self.assertRaisesRegex(ValueError, "REVIEW_GIT_FAILED"):
            R.verify_series(base, groups, [*patches[:4], patches[0]])
        modified = [*groups[:4], {**groups[4], "plugin-maintenance/worker.py": b"changed\n"}]
        with self.assertRaisesRegex(ValueError, "REVIEW_FINAL_BYTES_CHANGED"):
            R.verify_series(base, groups, R.create_series(base, modified))

    def test_native_hosts_and_endpoint_are_owned_by_the_fifth_unit(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(R, "read_source", return_value=b"fixture\n"):
                groups = R.select_sources(Path(temp))
            self.assertEqual(5, len(groups))
            self.assertEqual(set(R.NATIVE_FILES), set(groups[4]))
            self.assertTrue(set(R.NATIVE_HOST_FILES).issubset(groups[4]))
            for earlier in groups[:4]:
                self.assertFalse(set(R.NATIVE_FILES).intersection(earlier))
            self.assertNotIn("src/main/java/unrelated.java", groups[4])
            for suffix in ("service/EmbeddedDeploymentService.java",
                           "web/EmbeddedDeploymentController.java",
                           "dto/StartEmbeddedDeploymentRequest.java"):
                self.assertTrue(any(name.endswith(suffix) for name in groups[4]))
            self.assertTrue(any(name.endswith("V60__MaintenanceAtDeployment.sql") for name in groups[4]))
            self.assertTrue(any(name.endswith("V65__RuntimeActivation.sql") for name in groups[4]))
            self.assertIn("docs/plugin-maintenance/candidate-transfer.md", groups[3])
            self.assertIn("docs/plugin-maintenance/instance-archive.md", groups[3])
            self.assertIn("docs/plugin-maintenance/spider-delivery-standard.md", groups[3])
            self.assertIn("plugin-maintenance/scripts/instance_archive.py", groups[1])
            self.assertIn("plugin-maintenance/scripts/test_instance_archive.py", groups[1])

    def test_selected_python_tools_have_their_local_import_dependencies(self):
        groups = R.select_sources(R.ROOT)
        selected = {name for group in groups for name in group}
        prefix = "plugin-maintenance/scripts/"
        local = {path.stem: prefix + path.name for path in (R.ROOT / prefix).glob("*.py")}
        for name in sorted(selected):
            if not name.startswith(prefix) or not name.endswith(".py"):
                continue
            tree = ast.parse((R.ROOT / name).read_bytes(), filename=name)
            for node in ast.walk(tree):
                modules = []
                if isinstance(node, ast.Import):
                    modules = [alias.name.split(".")[0] for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    modules = [node.module.split(".")[0]]
                for module in modules:
                    if module in local:
                        self.assertTrue(local[module] in selected, name + " imports missing " + module)

    def test_runtime_operations_include_products_dependencies_and_public_examples(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(R, "read_source", return_value=b"fixture\n"):
                groups = R.select_sources(Path(temp))
        expected = {
            "plugin-maintenance/scripts/managed_broker_service.py",
            "plugin-maintenance/scripts/managed_runner_tunnel.py",
            "plugin-maintenance/scripts/managed_source_setup.py",
            "plugin-maintenance/scripts/requirements-tunnel.txt",
            *("plugin-maintenance/examples/" + name for name in R.RUNTIME_EXAMPLES),
        }
        self.assertTrue(expected <= groups[0].keys())
        self.assertIn("docs/plugin-maintenance/managed-runner-operations.md", groups[3])
        selected = {name for group in groups for name in group}
        self.assertFalse(any("/server/data/" in name or name.startswith("recovery/") for name in selected))
        self.assertFalse(any("runner-tunnels.json" in name and "/examples/" not in name for name in selected))

    def test_launcher_python_entrypoints_are_included_in_the_review(self):
        groups = R.select_sources(R.ROOT)
        selected = {name for group in groups for name in group}
        prefix = "plugin-maintenance/scripts/"
        launcher = (R.ROOT / (prefix + "start_local.ps1")).read_text(encoding="utf-8")
        helpers = set(re.findall(r"Join-Path \$PSScriptRoot '([^']+\.py)'", launcher))
        self.assertIn("managed_source_setup.py", helpers)
        self.assertIn("managed_runner_tunnel.py", helpers)
        for helper in helpers:
            self.assertIn(prefix + helper, selected, "start_local.ps1 invokes " + helper)

    def test_private_and_generated_paths_are_not_selected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prefix = "plugin-maintenance/web-ui/"
            fixtures = {
                prefix + "src/App.vue": b"<template/>",
                prefix + "package-lock.json": b"{}",
                prefix + "dist/index.html": b"private",
                prefix + "node_modules/private.js": b"private",
                prefix + "data/connections.json": b"private",
                prefix + "build.log": b"private",
            }
            R.put_files(root, fixtures)
            self.assertEqual([prefix + "package-lock.json", prefix + "src/App.vue"],
                             R.subtree(root, prefix))
            with self.assertRaisesRegex(ValueError, "REVIEW_PRIVATE_PATH"):
                R.read_source(root, prefix + "data/connections.json")

    def test_archive_is_reproducible_and_cannot_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = Path(temp) / "one.zip", Path(temp) / "two.zip"
            files = {"manifest.json": R.json_bytes({"published": False}), "01.patch": b"patch\n"}
            R.write_archive(first, files)
            R.write_archive(second, files)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with self.assertRaises(FileExistsError):
                R.write_archive(first, files)
            with zipfile.ZipFile(first) as archive:
                self.assertFalse(json.loads(archive.read("manifest.json"))["published"])
                self.assertEqual((1980, 1, 1, 0, 0, 0), archive.infolist()[0].date_time)

    def test_only_the_embedded_profile_may_change_host_pom(self):
        baseline = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles/></project>'
        current = (
            b'<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles>'
            b'<profile><id>maintenance-embedded</id></profile></profiles></project>')
        self.assertEqual(R.normalized_xml(baseline), R.normalized_xml(current, True))
        self.assertNotEqual(R.normalized_xml(baseline),
                            R.normalized_xml(current.replace(b"<profiles>", b"<name>other</name><profiles>"), True))
        with self.assertRaisesRegex(ValueError, "REVIEW_EMBEDDED_PROFILE_MISSING"):
            R.normalized_xml(baseline, True)

    def test_product_http_profile_is_allowed_without_unrelated_host_changes(self):
        baseline = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles/></project>'
        current = (
            b'<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles>'
            b'<profile><id>maintenance-http-acceptance</id></profile>'
            b'<profile><id>maintenance-embedded</id></profile></profiles></project>')
        self.assertEqual(R.normalized_xml(baseline), R.normalized_xml(current, True))
        changed = current.replace(b"<profiles>", b"<name>other</name><profiles>")
        self.assertNotEqual(R.normalized_xml(baseline), R.normalized_xml(changed, True))


if __name__ == "__main__":
    unittest.main()
