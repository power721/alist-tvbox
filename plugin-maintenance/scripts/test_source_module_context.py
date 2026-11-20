"""Offline context checks, not image or source-runtime acceptance."""
import hashlib
import unittest

import build_source_module_context as builder


class SourceModuleContextTest(unittest.TestCase):
    def test_preserves_bytes_and_records_identity_without_execution(self):
        body = b'raise RuntimeError("must not execute on the host")\n'
        files, manifest = builder.prepare({'companion.py': body})
        self.assertEqual({'Dockerfile', 'modules/companion.py'}, set(files))
        self.assertEqual(body, files['modules/companion.py'])
        self.assertEqual(hashlib.sha256(body).hexdigest(),
                         manifest['files']['modules/companion.py']['sha256'])
        self.assertFalse(manifest['sourceExecuted'])
        dockerfile = files['Dockerfile'].decode()
        self.assertIn("sysconfig.get_path('purelib')", dockerfile)
        self.assertTrue(dockerfile.rstrip().endswith('USER 65532:65532'))
        self.assertNotIn('pip install', dockerfile)

    def test_rejects_paths_and_invalid_input(self):
        for name in ('../bad.py', 'nested/module.py', 'module.pyc', '.py', 'C:\\module.py'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'NAME_INVALID'):
                builder.prepare({name: b'pass\n'})
        for body in (b'', 'pass', b'pass\n' * 524289):
            with self.subTest(kind=type(body).__name__), self.assertRaisesRegex(ValueError, 'BYTES_INVALID'):
                builder.prepare({'module.py': body})
        with self.assertRaisesRegex(ValueError, 'MODULES_REQUIRED'):
            builder.prepare({})

    def test_rejects_non_utf8_and_invalid_syntax(self):
        for body in (b'\xff', b'def broken(:\n', b'\x00'):
            with self.subTest(body=body), self.assertRaisesRegex(ValueError, 'SYNTAX_INVALID'):
                builder.prepare({'module.py': body})


if __name__ == '__main__':
    unittest.main()
