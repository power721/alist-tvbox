"""Bounded owner selection only; no NAS, broker RPC, Docker or source execution."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import managed_broker_retirement as R


class RetirementTest(unittest.TestCase):
    def document(self, *roots):
        return {"schemaVersion": 1, "ssh": {"hostEnv": "TEST_HOST"},
                "runners": [{"remoteSocket": root + "/executor.sock"} for root in roots]}

    def fixture(self, base, name, pid, *, state="S"):
        root = base / name
        root.mkdir()
        (root / "service-owner.json").write_text(json.dumps({"pid": pid}))
        process = base / "proc" / str(pid)
        process.mkdir(parents=True)
        args = ["/usr/bin/python3", "-I", str(root / "managed_broker_service.py"),
                "serve", "--config", str(root / "broker-service.json"), ""]
        (process / "cmdline").write_bytes(b"\0".join(value.encode() for value in args))
        (process / "stat").write_text(str(pid) + " (python3) " + state + " " + "0 " * 18 + "1234\n")
        return root, process

    def test_only_previous_roots_absent_from_current_are_selected(self):
        previous = self.document("/srv/old-a", "/srv/old-b", "/srv/shared")
        current = self.document("/srv/new-a", "/srv/shared")
        self.assertEqual(["/srv/old-a", "/srv/old-b"], R.superseded_roots(previous, current))
        self.assertEqual([], R.superseded_roots(current, current))
        current["ssh"]["hostEnv"] = "OTHER_HOST"
        with self.assertRaisesRegex(ValueError, "HOST_CHANGED"):
            R.superseded_roots(previous, current)

    def test_current_scope_and_parent_paths_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "SCOPE_INVALID"):
            R.broker_processes(["/srv/current"], ["/srv/current"])
        with self.assertRaisesRegex(ValueError, "CONFIG_INVALID"):
            R.superseded_roots(self.document("/srv/../other"), self.document("/srv/current"))

    def test_only_named_owner_process_is_read_without_process_enumeration(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            target, _ = self.fixture(base, "target", 123)
            self.fixture(base, "unrelated", 456)
            self.fixture(base, "current", 789)
            with patch.object(Path, "iterdir", side_effect=AssertionError("No global process scan")):
                rows = R.broker_processes([str(target)], [], proc=base / "proc")
            self.assertEqual([{"root": str(target), "pid": 123, "startTicks": "1234"}], rows)

    def test_reused_pid_and_zombie_are_not_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            changed, process = self.fixture(base, "changed", 123)
            zombie, _ = self.fixture(base, "zombie", 456, state="Z")
            (process / "cmdline").write_bytes(b"/usr/bin/python3\0other.py\0")
            rows = R.broker_processes([str(changed), str(zombie)], [], proc=base / "proc")
            self.assertTrue(all(row["skipped"] == "BROKER_RETIREMENT_PROCESS_CHANGED" for row in rows))

    def test_missing_owner_does_not_fall_back_to_a_host_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with patch.object(Path, "iterdir", side_effect=AssertionError("No fallback scan")):
                rows = R.broker_processes([str(root)], [], proc=root / "proc")
            self.assertEqual("FileNotFoundError", rows[0]["skipped"])


if __name__ == "__main__":
    unittest.main()
