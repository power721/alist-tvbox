"""Prepare authorized local plugin input without importing or executing its plaintext."""
from __future__ import annotations

import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = Path(__file__).resolve().parent / "runtime"
if not RUNTIME.is_dir():
    RUNTIME = ROOT / "server/src/main/resources/worker/runtime"
PRIVATE = ROOT.parent / ".codex/private-plugin-inputs"
LIMIT = 1048576


class InputFailure(ValueError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(condition, code):
    if not condition:
        raise InputFailure(code)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def load_runtime():
    manifest_body = (RUNTIME / "manifest.json").read_bytes()
    manifest = json.loads(manifest_body)
    record = manifest["files"]["Atvp.py"]
    path = RUNTIME / "Atvp.py"
    body = path.read_bytes()
    require(len(body) == record["bytes"] and digest(body) == record["sha256"],
            "PLUGIN_RUNTIME_IDENTITY_MISMATCH")
    spec = importlib.util.spec_from_file_location("maintenance_input_atvp", path)
    module = importlib.util.module_from_spec(spec)
    # Load the verified, bundled runtime only, never the supplied plugin.
    exec(compile(body, str(path), "exec"), module.__dict__)
    return module.Spider, {"atvpSha256": digest(body), "runtimeSha256": digest(manifest_body)}


def package_headers(text):
    headers, payloads = {}, 0
    for line in text.splitlines():
        if line.startswith("//@"):
            key, separator, value = line[3:].partition(":")
            require(separator and key and key not in headers, "PLUGIN_PACKAGE_INVALID")
            headers[key] = value
        elif line.startswith("payload.base64:"):
            payloads += 1
        else:
            require(not line.strip(), "PLUGIN_PACKAGE_INVALID")
    require(payloads == 1 and headers.get("format") == "secspider/1",
            "PLUGIN_PACKAGE_INVALID")
    return headers


def prepare(body, *, runtime_loader=load_runtime):
    require(type(body) is bytes and 0 < len(body) <= LIMIT, "PLUGIN_INPUT_SIZE_INVALID")
    try:
        text = body.decode("utf-8-sig")
    except UnicodeError:
        raise InputFailure("PLUGIN_INPUT_ENCODING_INVALID") from None
    first = next((line for line in text.splitlines() if line.strip()), "")
    encrypted = first.startswith(("//@", "payload.base64:"))
    identity = {}
    if encrypted:
        package_headers(text)
        try:
            spider_type, identity = runtime_loader()
            loader = spider_type()
            headers, payload = loader._parse_secspider_text(text)
        except ImportError:
            raise InputFailure("PLUGIN_CRYPTO_UNAVAILABLE") from None
        except (ValueError, KeyError, TypeError):
            raise InputFailure("PLUGIN_PACKAGE_INVALID") from None
        try:
            public_key = loader._deobfuscate_chunks(
                loader._public_key_chunks, loader.PUBLIC_KEY_XOR)
            # Verify explicitly: the bundled decryptor can otherwise skip missing EdDSA.
            loader._verify_signature(headers, payload, public_key)
        except ImportError:
            raise InputFailure("PLUGIN_CRYPTO_UNAVAILABLE") from None
        except (ValueError, TypeError):
            raise InputFailure("PLUGIN_SIGNATURE_REJECTED") from None
        try:
            plain = loader._decrypt_secspider_source(text).encode("utf-8")
        except ImportError:
            raise InputFailure("PLUGIN_CRYPTO_UNAVAILABLE") from None
        except (ValueError, KeyError, TypeError, UnicodeError):
            raise InputFailure("PLUGIN_DECRYPTION_REJECTED") from None
        require(headers["hash"] == "sha256:" + digest(plain), "PLUGIN_PLAINTEXT_HASH_MISMATCH")
    else:
        plain = body
    require(0 < len(plain) <= LIMIT, "PLUGIN_PLAINTEXT_SIZE_INVALID")
    try:
        # Atvp compiles UTF-8 text, not byte-source encoding declarations.
        source_text = plain.decode("utf-8")
        tree = compile(source_text, "source.py", "exec", ast.PyCF_ONLY_AST, dont_inherit=True)
        compile(tree, "source.py", "exec", dont_inherit=True)
    except (SyntaxError, ValueError, TypeError, RecursionError, UnicodeError):
        raise InputFailure("PLUGIN_PLAINTEXT_SYNTAX_INVALID") from None
    require(any(isinstance(node, ast.ClassDef) and node.name == "Spider" for node in tree.body),
            "PLUGIN_SPIDER_CLASS_MISSING")
    return plain, {
        "schemaVersion": 1,
        "inputFormat": "secspider/1" if encrypted else "python",
        "inputSha256": digest(body), "inputBytes": len(body),
        "plaintextSha256": digest(plain), "plaintextBytes": len(plain),
        **identity,
        "signatureVerified": True if encrypted else None,
        "declaredHashVerified": True if encrypted else None,
        "syntaxCompiled": True, "sourceExecuted": False,
        "authority": "local_preparation_only",
        "businessChainVerified": False, "publishEligible": False,
    }


def write_private_bundle(body, plain, evidence, *, root=PRIVATE):
    root = root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    destination = root / uuid.uuid4().hex
    destination.mkdir(mode=0o700)
    for name, content in (
        ("input.package", body),
        ("source.py", plain),
        ("evidence.json", json.dumps(evidence, ensure_ascii=True, indent=2).encode("utf-8") + b"\n"),
    ):
        with (destination / name).open("xb") as stream:
            stream.write(content)
        (destination / name).chmod(0o600)
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--authorized", action="store_true",
                        help="Confirm permission to restore this plugin's private plaintext.")
    args = parser.parse_args(argv)
    require(args.authorized, "PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED")
    if args.worker:
        try:
            body = sys.stdin.buffer.read(LIMIT + 1)
            plain, evidence = prepare(body)
            with Path("source.py").open("xb") as stream:
                stream.write(plain)
            Path("source.py").chmod(0o600)
            print(json.dumps({"status": "prepared", "evidence": evidence}))
        except InputFailure as error:
            print(json.dumps({"status": "rejected", "code": error.code}))
        return 0
    require(args.input is not None, "PLUGIN_INPUT_REQUIRED")
    with args.input.open("rb") as stream:
        body = stream.read(LIMIT + 1)
    plain, evidence = prepare(body)
    output = write_private_bundle(body, plain, evidence)
    print(json.dumps({"status": "prepared", "directory": str(output),
                      "evidence": evidence}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InputFailure as error:
        print(json.dumps({"status": "rejected", "code": error.code}), file=sys.stderr)
        raise SystemExit(1) from None
    except (OSError, ValueError, KeyError, TypeError, ImportError):
        print(json.dumps({"status": "rejected", "code": "PLUGIN_PREPARATION_FAILED"}), file=sys.stderr)
        raise SystemExit(1) from None
