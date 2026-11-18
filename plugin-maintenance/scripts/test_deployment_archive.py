"""Focused portable deployment checks; no Docker daemon or application is started."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import deployment_archive as tool


class DeploymentArchiveTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.data = self.root / "data"
        self.data.mkdir()
        (self.data / "atv.mv.db").write_bytes(b"trusted-cold-database")
        (self.data / "plugins").mkdir()
        (self.data / "plugins/source.py").write_bytes(b"raise RuntimeError('DO_NOT_EXECUTE')\r\n")
        self.jar = self.root / "application.jar"
        self.jar.write_bytes(b"trusted-jar-fixture")
        self.image = "sha256:" + "a" * 64
        config = {"schemaVersion": 1, "deployment": "at-embedded", "roots": {"data": str(self.data)},
                  "database": "data/atv.mv.db", "sqlite": [], "mounts": {"/data": "data", "/www/static": "data/plugins"},
                  "imageId": self.image, "credentialReferences": ["PRIVATE_REBIND_KEY"]}
        self.config = self.root / "instance.json"
        self.config.write_bytes(tool.instance.json_bytes(config))
        self.calls = []
        self.h2 = patch.object(tool.instance, "h2", side_effect=self.fake_h2)
        self.h2.start()
        self.addCleanup(self.h2.stop)
        self.process = patch.object(tool.subprocess, "run", side_effect=self.fake_docker)
        self.process.start()
        self.addCleanup(self.process.stop)
        self.cold = self.root / "cold.zip"
        self.cold_sha = tool.instance.backup(self.config, self.cold, "unused", self.jar)["archiveSha256"]

    @staticmethod
    def fake_h2(java, jar, deployment, arguments):
        if arguments[0] == "backup":
            with zipfile.ZipFile(arguments[-1], "w") as archive:
                archive.writestr("atv.mv.db", Path(arguments[2]).read_bytes())
        else:
            target = Path(arguments[4])
            target.mkdir()
            with zipfile.ZipFile(arguments[2]) as archive:
                (target / "atv.mv.db").write_bytes(archive.read("atv.mv.db"))
        return {"ok": True, "applicationStarted": False}

    def fake_docker(self, args, **kwargs):
        self.calls.append(args)
        operation = args[1:3]
        if operation == ["image", "inspect"]:
            body = json.dumps([{"Id": self.image, "Os": "linux", "Architecture": "amd64"}]).encode()
        elif operation == ["image", "save"]:
            self.assertEqual(args[-1], self.image)
            Path(args[4]).write_bytes(b"only-the-selected-image-fixture")
            body = b""
        elif operation == ["image", "load"]:
            self.assertEqual(Path(args[4]).read_bytes(), b"only-the-selected-image-fixture")
            body = b"loaded"
        else:
            self.fail("Unexpected Docker operation")
        return subprocess.CompletedProcess(args, 0, body, b"")

    def pack(self):
        path = self.root / "deployment.zip"
        receipt = tool.pack(self.cold, self.cold_sha, path, "fixture-docker")
        return path, receipt["archiveSha256"]

    def test_pack_restore_carries_image_instance_and_tools_without_starting(self):
        path, sha = self.pack()
        manifest = tool.read_archive(path, sha)
        self.assertEqual(manifest["image"]["id"], self.image)
        target = self.root / "restored"
        receipt = tool.restore(path, sha, target, "unused", "fixture-docker")
        self.assertTrue(receipt["imageLoaded"])
        self.assertFalse(receipt["applicationStarted"])
        self.assertEqual((target / "instance/state/data/atv.mv.db").read_bytes(), b"trusted-cold-database")
        self.assertEqual((target / "instance/state/data/plugins/source.py").read_bytes(),
                         (self.data / "plugins/source.py").read_bytes())
        config, roots = tool.instance.configuration(target / "instance/instance.json")
        self.assertEqual(roots["data"], target / "instance/state/data")
        self.assertEqual(config["mounts"]["/www/static"], "data/plugins")
        self.assertTrue((target / "runtime/deployment_archive.py").is_file())
        self.assertTrue((target / "runtime/instance_archive.py").is_file())
        self.assertEqual([args[1:3] for args in self.calls].count(["image", "save"]), 1)
        self.assertEqual([args[1:3] for args in self.calls].count(["image", "load"]), 1)

    def test_wrong_identity_tampering_and_existing_outputs_never_reach_docker_load(self):
        path, sha = self.pack()
        self.calls.clear()
        with self.assertRaisesRegex(tool.instance.ArchiveFailure, "IDENTITY_MISMATCH"):
            tool.restore(path, "0" * 64, self.root / "bad", "unused", "fixture-docker")
        target = self.root / "existing"
        target.mkdir()
        with self.assertRaisesRegex(tool.instance.ArchiveFailure, "TARGET_MUST_NOT_EXIST"):
            tool.restore(path, sha, target, "unused", "fixture-docker")
        changed = self.root / "changed.zip"
        with zipfile.ZipFile(path) as old, zipfile.ZipFile(changed, "w") as new:
            for name in old.namelist():
                new.writestr(name, b"broken" if name == "image.tar" else old.read(name))
        with self.assertRaisesRegex(tool.instance.ArchiveFailure, "FILE_INVALID"):
            tool.restore(changed, tool.instance.hash_file(changed), self.root / "bad", "unused", "fixture-docker")
        self.assertFalse(self.calls)
        self.assertFalse((self.root / "bad").exists())

    def test_h2_failure_preserves_archive_and_removes_only_new_restore_directory(self):
        path, sha = self.pack()
        original = path.read_bytes()
        target = self.root / "failed"
        with patch.object(tool.instance, "h2", side_effect=tool.instance.ArchiveFailure("DATABASE_AUTHENTICATION_FAILED")):
            with self.assertRaisesRegex(tool.instance.ArchiveFailure, "DATABASE_AUTHENTICATION_FAILED"):
                tool.restore(path, sha, target, "unused", "fixture-docker")
        self.assertFalse(target.exists())
        self.assertEqual(path.read_bytes(), original)
        self.assertTrue(self.data.is_dir())

    def test_missing_image_id_is_explicit_and_never_guesses_or_pulls(self):
        config = json.loads(self.config.read_bytes())
        config["imageId"] = None
        self.config.write_bytes(tool.instance.json_bytes(config))
        cold = self.root / "without-image.zip"
        sha = tool.instance.backup(self.config, cold, "unused", self.jar)["archiveSha256"]
        self.calls.clear()
        with self.assertRaisesRegex(tool.instance.ArchiveFailure, "DEPLOYMENT_IMAGE_REQUIRED"):
            tool.pack(cold, sha, self.root / "bad.zip", "fixture-docker")
        self.assertFalse(self.calls)


if __name__ == "__main__":
    unittest.main()
