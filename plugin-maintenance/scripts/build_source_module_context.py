"""Package explicit companion modules without importing them or running Docker."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import zipfile

MODULE = Path(__file__).resolve().parents[1]


def prepare(modules):
    if not modules:
        raise ValueError('SOURCE_MODULES_REQUIRED')
    files = {'Dockerfile': (MODULE / 'container/Dockerfile.source-modules').read_bytes()}
    for name, body in sorted(modules.items()):
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*\.py', name):
            raise ValueError('SOURCE_MODULE_NAME_INVALID')
        if not isinstance(body, bytes) or not 0 < len(body) <= 2097152:
            raise ValueError('SOURCE_MODULE_BYTES_INVALID')
        try:
            # Compile the AST only. Companion modules stay untrusted source inputs.
            compile(body.decode('utf-8-sig'), name, 'exec', flags=ast.PyCF_ONLY_AST,
                    dont_inherit=True)
        except (UnicodeError, SyntaxError, ValueError):
            raise ValueError('SOURCE_MODULE_SYNTAX_INVALID') from None
        files['modules/' + name] = body
    manifest = {
        'schemaVersion': 1, 'authority': 'expected_source_module_context',
        'files': {name: {'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()}
                  for name, body in files.items()},
        'sourceExecuted': False,
        'boundary': 'Explicit local companion modules only. No image build, dependency resolution, '
                    'source execution, or business validation; the caller must bind the base image identity.',
    }
    return files, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    modules = {}
    for path in args.module:
        if path.name in modules:
            raise ValueError('SOURCE_MODULE_NAME_DUPLICATE')
        modules[path.name] = path.read_bytes()
    files, manifest = prepare(modules)
    with zipfile.ZipFile(args.output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, body in files.items():
            archive.writestr(name, body)
        archive.writestr('module-context-manifest.json', json.dumps(manifest, indent=2))
    print(json.dumps({'output': str(args.output), 'moduleCount': len(modules),
                      'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      'sourceExecuted': False}))


if __name__ == '__main__':
    main()
