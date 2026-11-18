"""Focused standalone source-boundary and repeatability checks; no services or source execution."""
from pathlib import Path
import tempfile
import unittest

import prepare_standalone_release as release
import install_local_t4


class StandaloneReleaseTest(unittest.TestCase):
    def test_actual_source_archive_is_repeatable_and_independent_of_the_at_host(self):
        artifacts, manifest = release.payload(release.ROOT, "2026-11-18T00:00:00Z")
        self.assertIn("plugin-maintenance/server/pom.xml", artifacts)
        self.assertNotIn("pom.xml", artifacts)
        self.assertFalse(any("at-embedded/" in name or name.startswith("src/") for name in artifacts))
        self.assertIn("plugin-maintenance/web-ui/package-lock.json", artifacts)
        self.assertIn("plugin-maintenance/container/requirements.txt", artifacts)
        self.assertIn("plugin-maintenance/deployment/compose.t4.yaml", artifacts)
        for name in (*install_local_t4.FILES, "install_local_t4.py", "t4_install_bundle.py", "test_t4_install_bundle.py"):
            self.assertIn("plugin-maintenance/scripts/" + name, artifacts)
        self.assertIn("plugin-maintenance/server/src/main/resources/worker/managed-runtime.json", artifacts)
        with tempfile.TemporaryDirectory(prefix="standalone-source-test-") as temporary:
            left, right = Path(temporary) / "left.zip", Path(temporary) / "right.zip"
            release.review.write_archive(left, artifacts)
            release.review.write_archive(right, artifacts)
            self.assertEqual(left.read_bytes(), right.read_bytes())
            restored = Path(temporary) / "restored"
            self.assertEqual(release.restore_archive(left, restored), len(manifest["files"]))
            self.assertTrue(all((restored / name).read_bytes() == body for name, body in artifacts.items()))
        self.assertEqual(len(artifacts) - 1, len(manifest["files"]))
        self.assertFalse(manifest["publicDistributionReviewed"])

    def test_private_paths_and_secret_like_material_are_rejected(self):
        for name in ("plugin-maintenance/server/data/x", "../outside.py", "src/main/X.java",
                     "plugin-maintenance/scripts/connections.json", "plugin-maintenance/.codex/x"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.validate_files({name: b"data"})
        with self.assertRaises(ValueError):
            release.validate_files({"plugin-maintenance/scripts/x.py": b"sk-" + b"A" * 30})


if __name__ == "__main__":
    unittest.main()
