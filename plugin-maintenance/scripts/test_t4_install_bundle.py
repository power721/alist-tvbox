"""Focused offline package boundaries. No live Docker, sources or services."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import t4_install_bundle as B


class T4InstallBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="t4-bundle-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / "accepted"
        for name, body in {
            "scripts/install_local_t4.py": b"FILES=('manager.py',)\ndef install(*args): return args\n",
            "scripts/manager.py": b"# manager\n", "managed-source/entry.py": b"# entry\n",
            B.FIXTURE_ROOT + "worker.py": b"# worker\n",
            "vendor/m3u8/__init__.py": b"", "vendor/Crypto/__init__.py": b"",
            "vendor/other/private.json": b"not-exported", "data/connections.json": b"not-exported",
            "journal/old.json": b"not-exported", "sources/old.py": b"not-exported",
        }.items():
            self.put(self.runtime / name, body)
        fixture = {"files": {"worker.py": self.record(self.runtime / (B.FIXTURE_ROOT + "worker.py"))}}
        self.put(self.runtime / (B.FIXTURE_ROOT + "manifest.json"), json.dumps(fixture).encode())
        manifest = {"runtimeSha256": B.sha(self.runtime / (B.FIXTURE_ROOT + "manifest.json")),
                    "files": {"managed-source/entry.py": self.record(self.runtime / "managed-source/entry.py")}}
        self.put(self.runtime / B.RUNTIME_MANIFEST, json.dumps(manifest).encode())
        self.image = "sha256:" + "a" * 64
        self.templates = self.root / "templates.json"
        self.templates.write_text(json.dumps([{"imageId": self.image, "httpImageId": self.image,
            "bundleSha256": B.sha(self.runtime / B.RUNTIME_MANIFEST)}]), encoding="utf-8")

    @staticmethod
    def put(path, body):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)

    @staticmethod
    def record(path):
        return {"bytes": path.stat().st_size, "sha256": B.sha(path)}

    def pack(self):
        bundle = self.root / "bundle"
        def docker(args, **kwargs):
            self.assertEqual(args[:len(B.DOCKER)], B.DOCKER)
            self.assertEqual(args[-1], self.image)
            self.put(Path(args[args.index("--output") + 1]), b"pinned-image")
        with patch.object(B, "inspect_images", return_value=[{"Id": self.image}]), \
                patch.object(B.subprocess, "run", side_effect=docker):
            result = B.pack(self.runtime, self.templates, bundle, "test")
        return bundle, result

    def test_only_runtime_and_allowed_dependencies_are_exported(self):
        bundle, result = self.pack()
        value = B.verify(bundle, result["manifestSha256"])
        names = value["files"]
        self.assertIn("runtime/vendor/m3u8/__init__.py", names)
        self.assertIn("runtime/vendor/Crypto/__init__.py", names)
        self.assertFalse(any(word in Path(name).parts for name in names
                             for word in ("connections.json", "journal", "sources", "other")))
        self.assertEqual(value["imageIds"], [self.image])
        self.assertEqual(B.load_installer(bundle / "runtime").install(1, 2, 3), (1, 2, 3))
        self.assertFalse(value["credentialsIncluded"])

    def test_tampered_file_and_manifest_are_rejected_before_import(self):
        bundle, result = self.pack()
        (bundle / "images.tar").write_bytes(b"tampered")
        with patch.object(B.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "INSTALL_BUNDLE_FILE_CHANGED"):
                B.verify(bundle, result["manifestSha256"])
            command.assert_not_called()
        with self.assertRaisesRegex(ValueError, "INSTALL_BUNDLE_MANIFEST_CHANGED"):
            B.verify(bundle, "0" * 64)

    def test_path_escape_rejected_even_in_a_rehashed_manifest(self):
        bundle, _ = self.pack()
        manifest = json.loads((bundle / B.MANIFEST).read_bytes())
        manifest["files"]["../outside"] = {"bytes": 0, "sha256": hashlib.sha256(b"").hexdigest()}
        (bundle / B.MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "INSTALL_BUNDLE_PATH_INVALID"):
            B.verify(bundle, B.sha(bundle / B.MANIFEST))

    def test_changed_runtime_and_nonmatching_template_image_are_rejected(self):
        (self.runtime / "managed-source/entry.py").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "INSTALL_BUNDLE_RUNTIME_CHANGED"):
            B.runtime_files(self.runtime)
        with patch.object(B.subprocess, "run") as run:
            run.return_value.stdout = json.dumps([{"Id": "sha256:" + "b" * 64}]).encode()
            with self.assertRaisesRegex(ValueError, "INSTALL_BUNDLE_IMAGE_CHANGED"):
                B.inspect_images([self.image])


if __name__ == "__main__":
    unittest.main()
