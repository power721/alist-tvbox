"""Narrow packaging and startup checks; real embedded startup is checked on Linux."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_service_context as context


class ServiceContextTest(unittest.TestCase):
    def test_pinned_contexts_are_repeatable_and_require_current_web_and_schema(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "application.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name in ("static/index.html",
                             "db/maintenance/h2/V73__MaintenanceAutomation.sql"):
                    archive.writestr("BOOT-INF/classes/" + name, b"fixture")
            image = "example/runtime@sha256:" + "a" * 64
            for mode in ("embedded",):
                first, second = root / (mode + "1.zip"), root / (mode + "2.zip")
                left = context.build(mode, jar, image, first)
                right = context.build(mode, jar, image, second)
                self.assertEqual(first.read_bytes(), second.read_bytes())
                self.assertEqual(left["contextSha256"], right["contextSha256"])
                with zipfile.ZipFile(first) as archive:
                    manifest = json.loads(archive.read("service-context.json"))
                    self.assertEqual(manifest["runtimeImage"], image)
                    self.assertEqual(manifest["files"]["application.jar"]["sha256"], left["jarSha256"])
            with self.assertRaises(ValueError):
                context.build("embedded", jar, "example/runtime:latest", root / "bad.zip")
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/classes/static/index.html", b"old")
            with self.assertRaises(KeyError):
                context.build("embedded", jar, image, root / "old.zip")

    def test_entry_rejects_standalone_before_starting_java(self):
        spec = importlib.util.spec_from_file_location("service_entry", context.MODULE / "deployment/service_entry.py")
        entry = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(entry)
        with patch.dict(entry.os.environ, {"MAINTENANCE_DEPLOYMENT": "standalone"}, clear=True), \
                patch.object(entry.os, "execve") as execute:
            with self.assertRaisesRegex(SystemExit, "INVALID_DEPLOYMENT_MODE"):
                entry.main()
            execute.assert_not_called()

    def test_context_rejects_an_independent_web_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "application.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name in ("static/index.html", "maintenance-ui/index.html",
                             "db/maintenance/h2/V73__MaintenanceAutomation.sql"):
                    archive.writestr("BOOT-INF/classes/" + name, b"fixture")
            with self.assertRaisesRegex(ValueError, "SERVICE_CONTEXT_INDEPENDENT_UI_NOT_ALLOWED"):
                context.build("embedded", jar, "example/runtime@sha256:" + "a" * 64, root / "bad.zip")


if __name__ == "__main__":
    unittest.main()
