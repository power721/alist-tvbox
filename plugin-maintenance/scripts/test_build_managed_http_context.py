"""Product context packaging only: no Engine, source execution, or historical lab inputs."""
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import build_managed_http_context as H


class ContextTests(unittest.TestCase):
    def fixture(self, root, extra=None):
        path = root / "at-maintenance-http.jar"
        files = {
            "META-INF/MANIFEST.MF": ("Manifest-Version: 1.0\r\nStart-Class: " + H.MAIN + "\r\n\r\n").encode(),
            "BOOT-INF/classes/" + H.MAIN.replace(".", "/") + ".class": b"driver-fixture",
            "BOOT-INF/classes/cn/har01d/maintenance/lab/HttpAcceptanceConfiguration.class": b"config-fixture",
            "BOOT-INF/classes/cn/har01d/alist_tvbox/web/T4Controller.class": b"owner-fixture",
            "BOOT-INF/classes/cn/har01d/alist_tvbox/service/ManagedT4BrokerClient.class": b"broker-fixture",
            "BOOT-INF/classes/db/migration/h2/V1.sql": b"SELECT 1;",
            "BOOT-INF/classes/ua.txt": b"fixture",
            "BOOT-INF/lib/dependency.jar": b"dependency-fixture",
        }
        files.update(extra or {})
        with zipfile.ZipFile(path, "w") as archive:
            for name, body in files.items():
                archive.writestr(name, body)
        return path, H.digest(path.read_bytes())

    def prepare(self, path, sha):
        return H.prepare(path, sha, "fixed-base:local", "sha256:" + "a" * 64)

    def test_context_has_product_classes_migration_directories_and_no_test_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            path, sha = self.fixture(Path(temp))
            files, manifest = self.prepare(path, sha)
            with zipfile.ZipFile(io.BytesIO(files["lib/owners.jar"])) as archive:
                self.assertIn("db/migration/h2/", archive.namelist())
                self.assertNotIn("mockito-extensions/org.mockito.plugins.MockMaker", archive.namelist())
            self.assertEqual(sha, manifest["applicationJarSha256"])
            self.assertFalse(manifest["historicalOwnerReceiptRequired"])
            self.assertIn(b"USER 65532:65532", files["Dockerfile"])
            self.assertIn(b"RUN /jre/bin/java -version", files["Dockerfile"])
            self.assertEqual("sha256:" + "a" * 64, manifest["baseImageId"])

    def test_wrong_jar_or_application_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path, sha = self.fixture(Path(temp))
            with self.assertRaisesRegex(ValueError, "JAR_CHANGED"):
                self.prepare(path, "b" * 64)
            path, sha = self.fixture(Path(temp), {
                "META-INF/MANIFEST.MF": b"Start-Class: another.Application\r\n\r\n"})
            with self.assertRaisesRegex(ValueError, "WRONG_APPLICATION"):
                self.prepare(path, sha)

    def test_private_resources_test_classes_and_test_dependencies_are_rejected(self):
        for name, code in (
                ("BOOT-INF/classes/connections.json", "UNEXPECTED_RESOURCE"),
                ("BOOT-INF/classes/owner/SomeTest.class", "TEST_CLASS"),
                ("BOOT-INF/lib/mockito-core-1.jar", "TEST_DEPENDENCY")):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                path, sha = self.fixture(Path(temp), {name: b"not-a-runtime-input"})
                with self.assertRaisesRegex(ValueError, code):
                    self.prepare(path, sha)

    def test_export_is_reproducible_and_does_not_replace_an_artifact(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path, sha = self.fixture(root)
            first, second = self.prepare(path, sha), self.prepare(path, sha)
            self.assertEqual(first, second)
            one, two = root / "one.zip", root / "two.zip"
            H.export(one, *first)
            H.export(two, *second)
            self.assertEqual(one.read_bytes(), two.read_bytes())
            with self.assertRaises(FileExistsError):
                H.export(one, *first)
            with zipfile.ZipFile(one) as archive:
                self.assertEqual(sha, json.loads(archive.read("build-context-manifest.json"))["applicationJarSha256"])


if __name__ == "__main__":
    unittest.main()
