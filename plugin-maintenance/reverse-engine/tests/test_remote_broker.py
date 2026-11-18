import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('remote_broker', ROOT / 'reverse-engine/remote_broker.py')
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)
TASK = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'


class RemoteBrokerTest(unittest.TestCase):
    def invoke(self, payload, lines=b''):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, '-I', '-c', remote.BOOTSTRAP, TASK],
                                    input=payload + lines, capture_output=True, timeout=10,
                                    env={**os.environ, 'TEMP': directory, 'TMP': directory, 'TMPDIR': directory})
            self.assertEqual([], list(Path(directory).iterdir()))
            return result

    def test_same_broker_protocol_runs_in_a_cleaned_task_directory(self):
        request = {'requestId': 1, 'value': {'discoverOrigins': 'host="https://example.org"'}}
        result = self.invoke(remote.bundle(ROOT / 'scripts/source_egress.py', TASK),
                             json.dumps(request).encode() + b'\n')
        self.assertEqual(0, result.returncode, result.stderr)
        rows = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual({'transportState': {'phase': 'started', 'taskId': TASK}}, rows[0])
        self.assertEqual({'requestId': 1, 'result': {'origins': ['https://example.org']}}, rows[1])
        self.assertEqual({'transportState': {'phase': 'cleaned', 'taskId': TASK}}, rows[-1])
        self.assertEqual(b'', result.stderr)

    def test_changed_bundle_is_rejected_without_traceback_or_leftover_files(self):
        payload = json.loads(remote.bundle(ROOT / 'scripts/source_egress.py', TASK))
        payload['files']['broker.py']['sha256'] = '0' * 64
        result = self.invoke(json.dumps(payload).encode() + b'\n')
        self.assertEqual(1, result.returncode)
        self.assertEqual(b'', result.stdout)
        self.assertEqual(b'', result.stderr)


if __name__ == '__main__':
    unittest.main()
