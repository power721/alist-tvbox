"""Focused instance orchestration checks; H2 is mocked except the opt-in real round trip."""
import json
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import instance_archive as tool


class InstanceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        data, alist = self.root / "data", self.root / "alist"
        data.mkdir()
        alist.mkdir()
        self.body = b"\xef\xbb\xbf# data only\r\nraise RuntimeError('DO_NOT_EXECUTE')\r\n"
        (data / "local").mkdir()
        (data / "empty").mkdir()
        (data / "local" / "\u722c\u866b.py").write_bytes(self.body)
        (data / "atv.mv.db").write_bytes(b"owned-h2-fixture")
        (alist / "config.json").write_text('{"jwt_secret":"PRIVATE_FIXTURE"}', encoding="utf-8")
        self.sqlite = alist / "data.db"
        self.db = sqlite3.connect(self.sqlite)
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE settings(id TEXT PRIMARY KEY, value TEXT)")
        self.db.execute("INSERT INTO settings VALUES('fixture', 'PRIVATE_FIXTURE')")
        self.db.commit()
        self.jar = self.root / "application.jar"
        self.jar.write_bytes(b"trusted-jar-fixture")
        self.config = self.root / "instance.json"
        self.settings = {
            "schemaVersion": 1, "deployment": "at-embedded",
            "roots": {"data": str(data), "alist": str(alist)},
            "mounts": {"/data": "data", "/www/static": "data/local", "/opt/alist/data": "alist"},
            "database": "data/atv.mv.db", "sqlite": ["alist/data.db"],
            "imageId": "sha256:" + "1" * 64, "credentialReferences": ["INSTANCE_PRIVATE_KEY"],
        }
        self.config.write_bytes(tool.json_bytes(self.settings))
        self.mock = patch.object(tool, "h2", side_effect=self.fake_h2)
        self.mock.start()
        self.addCleanup(self.mock.stop)

    @staticmethod
    def fake_h2(java, jar, deployment, arguments):
        filename = "atv.mv.db" if deployment == "at-embedded" else "maintenance.mv.db"
        if arguments[0] == "backup":
            with zipfile.ZipFile(arguments[-1], "w") as archive:
                archive.writestr(filename, Path(arguments[2]).read_bytes())
        else:
            output = Path(arguments[4])
            output.mkdir()
            with zipfile.ZipFile(arguments[2]) as archive:
                (output / filename).write_bytes(archive.read(filename))
        return {"ok": True, "fixture": True, "applicationStarted": False}

    def backup(self):
        target = self.root / "owned.zip"
        result = tool.backup(self.config, target, "unused-fixture-java", self.jar)
        return target, result["archiveSha256"]

    def rewrite(self, archive, change):
        with zipfile.ZipFile(archive) as source:
            files = {name: source.read(name) for name in source.namelist()}
        change(files)
        target = self.root / "changed.zip"
        with zipfile.ZipFile(target, "w") as output:
            for name, body in files.items():
                output.writestr(name, body)
        return target, tool.hash_file(target)

    def test_full_files_sqlite_wal_runtime_and_private_references_round_trip(self):
        with patch.dict(os.environ, {"INSTANCE_PRIVATE_KEY": "ENV_SECRET_NOT_EXPORTED"}):
            archive, sha = self.backup()
            manifest = tool.read_archive(archive, sha)
            self.assertEqual(manifest["sqlite"]["alist/data.db"]["tables"], {"settings": 1})
            self.assertNotIn("ENV_SECRET_NOT_EXPORTED", json.dumps(manifest))
            self.assertNotIn("PRIVATE_FIXTURE", json.dumps(manifest))
            self.assertEqual(manifest["credentialReferences"], ["INSTANCE_PRIVATE_KEY"])
            target = self.root / "restored"
            result = tool.restore(archive, target, sha, "unused-fixture-java")
        self.assertFalse(result["applicationStarted"])
        self.assertEqual((target / "state/data/local/\u722c\u866b.py").read_bytes(), self.body)
        self.assertTrue((target / "state/data/empty").is_dir())
        self.assertEqual((target / tool.APPLICATION).read_bytes(), self.jar.read_bytes())
        self.assertTrue((target / tool.TOOL).is_file())
        self.assertFalse((target / "state/alist/data.db-wal").exists())
        self.assertEqual(json.loads((target / "instance.json").read_bytes())["mounts"],
                         self.settings["mounts"])
        with closing(sqlite3.connect(target / "state/alist/data.db")) as connection:
            self.assertEqual(connection.execute("SELECT value FROM settings").fetchone()[0], "PRIVATE_FIXTURE")
        tool.configuration(target / "instance.json")

    def test_wrong_trusted_identity_and_existing_targets_never_overwrite(self):
        archive, sha = self.backup()
        original = archive.read_bytes()
        with self.assertRaisesRegex(tool.ArchiveFailure, "TARGET_MUST_NOT_EXIST"):
            tool.backup(self.config, archive, "unused", self.jar)
        self.assertEqual(archive.read_bytes(), original)
        target = self.root / "restored"
        with self.assertRaisesRegex(tool.ArchiveFailure, "IDENTITY_MISMATCH"):
            tool.restore(archive, target, "0" * 64, "unused")
        self.assertFalse(target.exists())
        target.mkdir()
        (target / "keep").write_text("keep")
        with self.assertRaisesRegex(tool.ArchiveFailure, "TARGET_MUST_NOT_EXIST"):
            tool.restore(archive, target, sha, "unused")
        self.assertEqual((target / "keep").read_text(), "keep")

    def test_changed_file_and_unlisted_traversal_are_rejected_before_h2_restore(self):
        archive, _ = self.backup()
        changed, sha = self.rewrite(archive, lambda files: files.update(
            {"state/data/local/\u722c\u866b.py": b"altered"}))
        with self.assertRaises(tool.ArchiveFailure):
            tool.restore(changed, self.root / "bad", sha, "unused")
        changed.unlink()
        changed, sha = self.rewrite(archive, lambda files: files.update({"../escaped": b"no"}))
        with self.assertRaisesRegex(tool.ArchiveFailure, "ENTRIES_INVALID"):
            tool.restore(changed, self.root / "bad", sha, "unused")
        self.assertFalse((self.root / "bad").exists())
        self.assertFalse((self.root / "escaped").exists())

    def test_partial_failure_does_not_publish_or_hide_h2_error(self):
        with patch.object(tool, "h2", side_effect=tool.ArchiveFailure("DATABASE_IN_USE")):
            with self.assertRaisesRegex(tool.ArchiveFailure, "DATABASE_IN_USE"):
                self.backup()
        self.assertFalse((self.root / "owned.zip").exists())
        archive, sha = self.backup()
        with patch.object(tool, "h2", side_effect=tool.ArchiveFailure("DATABASE_AUTHENTICATION_FAILED")):
            with self.assertRaisesRegex(tool.ArchiveFailure, "DATABASE_AUTHENTICATION_FAILED"):
                tool.restore(archive, self.root / "bad", sha, "unused")
        self.assertFalse((self.root / "bad").exists())
        self.assertFalse(list(self.root.glob(".instance-*")))

    def test_config_rejects_secret_values_and_overlapping_roots(self):
        self.settings["credentialValues"] = {"key": "not-allowed"}
        self.config.write_bytes(tool.json_bytes(self.settings))
        with self.assertRaisesRegex(tool.ArchiveFailure, "CONFIG_INVALID"):
            tool.configuration(self.config)
        del self.settings["credentialValues"]
        self.settings["roots"]["nested"] = str(self.root / "data/local")
        self.config.write_bytes(tool.json_bytes(self.settings))
        with self.assertRaisesRegex(tool.ArchiveFailure, "ROOTS_OVERLAP"):
            tool.configuration(self.config)

    def test_different_jar_is_not_run_during_restore(self):
        archive, sha = self.backup()
        other = self.root / "other.jar"
        other.write_bytes(b"different")
        with self.assertRaisesRegex(tool.ArchiveFailure, "JAR_IDENTITY_MISMATCH"):
            tool.restore(archive, self.root / "bad", sha, "unused", other)
        self.assertFalse((self.root / "bad").exists())

    def test_standalone_uses_original_maintenance_database_without_alist(self):
        self.settings.update(
            deployment="standalone", database="data/maintenance.mv.db", sqlite=[],
            roots={"data": str(self.root / "data")}, mounts={"/data": "data"}, imageId=None)
        (self.root / "data/atv.mv.db").rename(self.root / "data/maintenance.mv.db")
        self.config.write_bytes(tool.json_bytes(self.settings))
        archive, sha = self.backup()
        result = tool.restore(archive, self.root / "standalone", sha, "unused")
        self.assertEqual(result["deployment"], "standalone")
        self.assertEqual(result["sqliteCount"], 0)
        self.assertEqual((self.root / "standalone/state/data/maintenance.mv.db").read_bytes(),
                         b"owned-h2-fixture")
        self.assertFalse((self.root / "standalone/state/alist").exists())


@unittest.skipUnless(os.environ.get("INSTANCE_TEST_DATABASE"), "explicit owned H2 fixture required")
class RealInstanceArchiveTest(unittest.TestCase):
    def test_current_jar_h2_sqlite_external_files_and_runtime_restore_together(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            data, alist = root / "data", root / "alist"
            data.mkdir()
            alist.mkdir()
            import shutil
            shutil.copyfile(os.environ["INSTANCE_TEST_DATABASE"], data / "atv.mv.db")
            (data / "local.py").write_bytes(b"# preserved source data only\r\n")
            with closing(sqlite3.connect(alist / "data.db")) as connection:
                connection.execute("CREATE TABLE owned_fixture(id INTEGER PRIMARY KEY, value TEXT)")
                connection.execute("INSERT INTO owned_fixture VALUES(1, 'preserved')")
                connection.commit()
            config = root / "instance.json"
            config.write_bytes(tool.json_bytes({
                "schemaVersion": 1, "deployment": "at-embedded",
                "roots": {"data": str(data), "alist": str(alist)},
                "database": "data/atv.mv.db", "sqlite": ["alist/data.db"],
            }))
            jar, java = Path(os.environ["INSTANCE_TEST_JAR"]), os.environ["INSTANCE_TEST_JAVA"]
            result = tool.backup(config, root / "owned.zip", java, jar)
            manifest = tool.read_archive(root / "owned.zip", result["archiveSha256"])
            result = tool.restore(root / "owned.zip", root / "restored", result["archiveSha256"], java)
            self.assertFalse(result["applicationStarted"])
            recovered = json.loads((root / "restored/recovery-manifest.json").read_bytes())
            tables = recovered["database"]["manifest"]["schema"]["tables"]
            self.assertEqual(tables["MAINTENANCE_SOURCE"], 1)
            self.assertEqual(tables["PLUGIN"], 1)
            self.assertEqual(manifest["sqlite"]["alist/data.db"]["tables"], {"owned_fixture": 1})
            self.assertEqual((root / "restored/state/data/local.py").read_bytes(),
                             (data / "local.py").read_bytes())
            self.assertEqual(tool.hash_file(root / "restored" / tool.APPLICATION), tool.hash_file(jar))
            import subprocess
            result = subprocess.run([
                java, "-cp", os.environ["INSTANCE_TEST_VERIFY_CP"], "InstanceArchiveFixture", "verify",
                str(root / "restored/state/data/atv.mv.db"),
            ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertEqual(result.returncode, 0, result.stdout.decode("utf-8", errors="replace"))
            self.assertIn(b"OWNED_H2_BYTES_AND_BUDGET_VERIFIED", result.stdout)


if __name__ == "__main__":
    unittest.main()
