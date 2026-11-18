"""Run on POSIX; use only an independent temporary fixture directory."""
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import nas_directory_scan as scanner


@unittest.skipUnless(os.name == "posix", "POSIX fd/no-follow contract")
class DirectoryScanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="at-inventory-test-")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def scan(self):
        return scanner.scan(str(self.root))

    def test_exact_bytes_without_execution(self):
        body = b'\xef\xbb\xbfraise RuntimeError("DO_NOT_EXECUTE")\r\n'
        (self.root / "source.py").write_bytes(body)
        (self.root / "source.js").write_bytes(b'throw Error("DO_NOT_EXECUTE")\n')
        result = self.scan()
        self.assertTrue(result["scan"]["complete"])
        row = next(row for row in result["files"] if row["path"] == "source.py")
        self.assertEqual(row["sha256"], hashlib.sha256(body).hexdigest())
        self.assertEqual(set(row), {"path", "kind", "bytes", "sha256", "status"})

    def test_config_excluded_and_symlinks_not_followed(self):
        (self.root / ".env").write_text("PRIVATE_SENTINEL")
        (self.root / "config.json").write_text("PRIVATE_SENTINEL")
        hidden = self.root / "node_modules"
        hidden.mkdir()
        (hidden / "hidden.py").write_text("PRIVATE_SENTINEL")
        (self.root / "link.py").symlink_to(self.root / ".env")
        (self.root / "linkdir").symlink_to(hidden, target_is_directory=True)
        result = self.scan()
        self.assertEqual(len(result["files"]), 1)
        self.assertEqual(result["files"][0]["status"], "symlink")
        self.assertIsNone(result["files"][0]["sha256"])
        self.assertNotIn("PRIVATE_SENTINEL", str(result))
        self.assertFalse(result["scan"]["complete"])

    def test_root_symlink_rejected(self):
        root = self.root / "real"
        root.mkdir()
        link = self.root / "link"
        link.symlink_to(root, target_is_directory=True)
        with self.assertRaises(OSError):
            scanner.scan(str(link))

    def test_invalid_utf8_empty_binary_and_oversize(self):
        for name, body in (("bad.py", b"\xff"), ("empty.py", b""), ("binary.js", b"a\0b"),
                           ("big.py", b"x" * (scanner.LIMITS["fileBytes"] + 1))):
            (self.root / name).write_bytes(body)
        result = self.scan()
        self.assertTrue(all(row["sha256"] is None for row in result["files"]))
        self.assertEqual(result["scan"]["sourceBytesRead"], 4)

    def test_hardlinks_not_read(self):
        (self.root / "a.py").write_text("PRIVATE_SENTINEL")
        os.link(self.root / "a.py", self.root / "b.py")
        result = self.scan()
        self.assertEqual(result["scan"]["sourceBytesRead"], 0)
        self.assertTrue(all(row["status"] == "changed_or_linked" for row in result["files"]))

    def test_budgets_stop_without_false_complete(self):
        for i in range(4):
            (self.root / (str(i) + ".py")).write_text("x = 1")
        with patch.dict(scanner.LIMITS, entries=2):
            result = self.scan()
        self.assertEqual(result["scan"]["visitedEntries"], 2)
        self.assertFalse(result["scan"]["complete"])
        with patch.dict(scanner.LIMITS, totalBytes=3):
            result = self.scan()
        self.assertEqual(result["scan"]["sourceBytesRead"], 0)
        self.assertFalse(result["scan"]["complete"])

    def test_source_and_depth_budgets(self):
        for i in range(3):
            (self.root / (str(i) + ".py")).write_text("x = 1")
        nested = self.root / "nested"
        nested.mkdir()
        (nested / "child.py").write_text("x = 2")
        with patch.dict(scanner.LIMITS, sources=1):
            self.assertEqual(len(self.scan()["files"]), 1)
            self.assertFalse(self.scan()["scan"]["complete"])
        with patch.dict(scanner.LIMITS, depth=0):
            result = self.scan()
            self.assertFalse(result["scan"]["complete"])
            self.assertEqual(result["scan"]["blockedDirectories"], 1)

    def test_replaced_file_is_not_hashed(self):
        (self.root / "a.py").write_text("x = 1")
        original = os.fstat
        count = 0

        def changed(fd):
            nonlocal count
            count += 1
            value = original(fd)
            if count == 1:
                (self.root / "a.py").write_text("x = 12345")
            return value

        with patch.object(scanner.os, "fstat", side_effect=changed):
            result = self.scan()
        self.assertIsNone(result["files"][0]["sha256"])
        self.assertEqual(result["files"][0]["status"], "changed_or_linked")


if __name__ == "__main__":
    unittest.main()
