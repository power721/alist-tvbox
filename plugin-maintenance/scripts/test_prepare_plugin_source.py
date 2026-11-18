"""Offline self-owned secspider fixtures; production keys and plugin code are never used."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from Crypto.Cipher import AES
from Crypto.Hash import SHA256
from Crypto.Protocol.KDF import HKDF
from Crypto.PublicKey import ECC
from Crypto.Random import get_random_bytes
from Crypto.Signature import eddsa

import prepare_plugin_source as P

SOURCE = b'from base.spider import Spider\nclass Spider(Spider):\n    def init(self, extend=""): pass\n'


def fixture(source=SOURCE, *, declared_hash=None):
    runtime_type, identity = P.load_runtime()
    key = ECC.generate(curve="Ed25519")
    master = get_random_bytes(32).hex()

    class OwnedSpider(runtime_type):
        _instance = None
        _public_key_chunks = runtime_type._obfuscate_text_for_test(
            key.public_key().export_key(format="PEM"), runtime_type.PUBLIC_KEY_XOR)
        _master_secret_chunks = runtime_type._obfuscate_text_for_test(master, runtime_type.MASTER_SECRET_XOR)

    headers = {
        "name": "\u81ea\u5efa\u6837\u672c", "version": "1", "remark": "", "id": "owned_fixture",
        "format": "secspider/1", "alg": "aes-256-gcm", "wrap": "hkdf-aes-keywrap",
        "sign": "ed25519", "kid": "ephemeral-test-key",
        "nonce": "base64:" + base64.b64encode(get_random_bytes(12)).decode(),
        "hash": "sha256:" + (declared_hash or P.digest(source)),
    }
    wrap_key = HKDF(master.encode(), 32, headers["kid"].encode(), SHA256,
                    context=("secspider:" + headers["name"] + ":1:wrap-key").encode())
    wrap_nonce = HKDF(master.encode(), 12, headers["kid"].encode(), SHA256,
                      context=("secspider:" + headers["name"] + ":1:wrap-nonce").encode())
    content_key = get_random_bytes(32)
    cipher = AES.new(wrap_key, AES.MODE_GCM, nonce=wrap_nonce)
    encrypted, tag = cipher.encrypt_and_digest(content_key)
    headers["ek"] = "base64:" + base64.b64encode(encrypted + tag).decode()
    cipher = AES.new(content_key, AES.MODE_GCM, nonce=base64.b64decode(headers["nonce"][7:]))
    encrypted, tag = cipher.encrypt_and_digest(source)
    payload = base64.b64encode(encrypted + tag).decode()
    signing = OwnedSpider()._build_signing_bytes(headers, payload)
    headers["sig"] = "base64:" + base64.b64encode(eddsa.new(key, "rfc8032").sign(signing)).decode()
    body = ("\n".join("//@" + name + ":" + value for name, value in headers.items())
            + "\npayload.base64:" + payload + "\n").encode()
    return body, lambda: (OwnedSpider, identity)


class PreparationTest(unittest.TestCase):
    def test_raw_source_preserves_exact_bytes_without_loading_crypto_runtime(self):
        for body in (SOURCE, SOURCE.replace(b"\n", b"\r\n")):
            with self.subTest(body=P.digest(body)), patch.object(P, "load_runtime") as runtime:
                plain, evidence = P.prepare(body, runtime_loader=runtime)
                self.assertEqual(body, plain)
                self.assertEqual(evidence["inputSha256"], evidence["plaintextSha256"])
                self.assertIsNone(evidence["signatureVerified"])
                self.assertFalse(evidence["sourceExecuted"])
                runtime.assert_not_called()

    def test_actual_atvp_decryptor_restores_three_owned_plaintexts(self):
        for source in (SOURCE, SOURCE + b"# non-executed variant\n",
                       SOURCE + "# \u4e2d\u6587\u4e0eCRLF\n".encode("utf-8")):
            with self.subTest(sha=P.digest(source)):
                package, runtime = fixture(source)
                plain, evidence = P.prepare(package, runtime_loader=runtime)
                self.assertEqual(source, plain)
                self.assertEqual(P.digest(package), evidence["inputSha256"])
                self.assertEqual(P.digest(source), evidence["plaintextSha256"])
                self.assertNotEqual(evidence["inputSha256"], evidence["plaintextSha256"])
                self.assertTrue(evidence["signatureVerified"])
                self.assertTrue(evidence["declaredHashVerified"])
                self.assertFalse(evidence["businessChainVerified"])
                self.assertFalse(evidence["publishEligible"])

    def test_restored_bundled_fixture_runs_through_the_existing_t4_worker(self):
        source = (P.ROOT / "fixtures/demo.py").read_bytes()
        package, runtime = fixture(source)
        plain, evidence = P.prepare(package, runtime_loader=runtime)
        with tempfile.TemporaryDirectory() as temporary:
            folder = P.write_private_bundle(package, plain, evidence, root=Path(temporary))
            outer = base64.b64encode(json.dumps({
                "source": str(folder / "source.py"), "raw": True}).encode()).decode()
            calls = [
                ("init", {"extend": outer}), ("home", {"filter": True}),
                ("category", {"t": "demo", "pg": "2", "filter": True, "extend": {}}),
                ("detail", {"ids": "demo-1"}),
                ("play", {"flag": "demo", "play": "demo-play"}),
                ("search", {"wd": "fixture", "pg": "2", "quick": False}), ("destroy", {}),
            ]
            body = b"".join((json.dumps({"id": str(index), "method": method, "params": params})
                             + "\n").encode() for index, (method, params) in enumerate(calls))
            env = {key: value for key, value in os.environ.items()
                   if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
            env["ATVP_CACHE_FILE"] = str(folder / "cache.json")
            completed = subprocess.run([
                sys.executable, "-I", str(P.ROOT / "scripts/fixture_runtime_runner.py"),
                str(P.RUNTIME / "t4worker.py"), str(P.RUNTIME / "Atvp.py")],
                input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                cwd=temporary, env=env, timeout=15, check=True)
            replies = [json.loads(line) for line in completed.stdout.splitlines()]
            self.assertEqual([str(index) for index in range(len(calls))],
                             [row["id"] for row in replies])
            self.assertTrue(all(row["ok"] for row in replies), replies)
            self.assertTrue(replies[1]["result"]["class"])
            self.assertEqual(2, replies[2]["result"]["page"])
            self.assertTrue(replies[3]["result"]["list"][0]["vod_play_url"])
            self.assertEqual("https://example.invalid/fixture.mp4", replies[4]["result"]["url"])
            self.assertEqual("fixture:2", replies[5]["result"]["list"][0]["vod_name"])
            self.assertFalse(evidence["businessChainVerified"])

    def test_tampered_signature_and_wrong_runtime_are_rejected_without_plaintext(self):
        package, runtime = fixture()
        tampered = package.replace(b"//@id:owned_fixture", b"//@id:tampered")
        with self.assertRaisesRegex(P.InputFailure, "^PLUGIN_SIGNATURE_REJECTED$"):
            P.prepare(tampered, runtime_loader=runtime)
        _, wrong_runtime = fixture()
        with self.assertRaisesRegex(P.InputFailure, "^PLUGIN_SIGNATURE_REJECTED$"):
            P.prepare(package, runtime_loader=wrong_runtime)

    def test_signed_but_wrong_plaintext_hash_and_broken_ciphertext_are_rejected(self):
        package, runtime = fixture(declared_hash="0" * 64)
        with self.assertRaisesRegex(P.InputFailure, "^PLUGIN_DECRYPTION_REJECTED$"):
            P.prepare(package, runtime_loader=runtime)
        package, runtime = fixture()
        spider_type, identity = runtime()
        with patch.object(spider_type, "_decrypt_secspider_source", side_effect=ValueError("PRIVATE")):
            with self.assertRaisesRegex(P.InputFailure, "^PLUGIN_DECRYPTION_REJECTED$") as result:
                P.prepare(package, runtime_loader=lambda: (spider_type, identity))
            self.assertNotIn("PRIVATE", str(result.exception))

    def test_missing_eddsa_cannot_reach_the_runtime_skip_verification_branch(self):
        package, runtime = fixture()
        spider_type, identity = runtime()
        with patch.object(spider_type, "_verify_signature", side_effect=ImportError("PRIVATE")), \
                patch.object(spider_type, "_decrypt_secspider_source") as decrypt:
            with self.assertRaisesRegex(P.InputFailure, "^PLUGIN_CRYPTO_UNAVAILABLE$"):
                P.prepare(package, runtime_loader=lambda: (spider_type, identity))
            decrypt.assert_not_called()

    def test_duplicate_headers_payloads_and_unknown_formats_are_not_raw_fallbacks(self):
        package, runtime = fixture()
        for body in (package + b"//@id:duplicate\n", package + b"payload.base64:AAAA\n",
                     package.replace(b"secspider/1", b"secspider/2"), package + b"unexpected\n"):
            with self.subTest(sha=P.digest(body)), self.assertRaisesRegex(
                    P.InputFailure, "^PLUGIN_PACKAGE_INVALID$"):
                P.prepare(body, runtime_loader=runtime)

    def test_invalid_python_and_missing_spider_reject_without_execution(self):
        for body, code in ((b"class Spider(:", "PLUGIN_PLAINTEXT_SYNTAX_INVALID"),
                           (b"\xef\xbb\xbf" + SOURCE, "PLUGIN_PLAINTEXT_SYNTAX_INVALID"),
                           (b"print('DO_NOT_EXECUTE')\n", "PLUGIN_SPIDER_CLASS_MISSING"),
                           (b"\xff", "PLUGIN_INPUT_ENCODING_INVALID"),
                           (b"", "PLUGIN_INPUT_SIZE_INVALID"),
                           (b"x" * (P.LIMIT + 1), "PLUGIN_INPUT_SIZE_INVALID")):
            with self.subTest(code=code), self.assertRaisesRegex(P.InputFailure, "^" + code + "$"):
                P.prepare(body)
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / "must-not-exist"
            malicious = SOURCE + ("\nopen(" + repr(str(marker)) + ", 'w').write('BAD')\n").encode()
            package, runtime = fixture(malicious)
            plain, _ = P.prepare(package, runtime_loader=runtime)
            self.assertEqual(malicious, plain)
            self.assertFalse(marker.exists())

    def test_envelope_bom_preserves_input_identity_without_changing_signed_plaintext(self):
        package, runtime = fixture()
        wrapped = b"\xef\xbb\xbf" + package
        plain, evidence = P.prepare(wrapped, runtime_loader=runtime)
        self.assertEqual(SOURCE, plain)
        self.assertEqual(P.digest(wrapped), evidence["inputSha256"])
        self.assertTrue(evidence["signatureVerified"])

    def test_private_bundle_has_exact_source_and_no_secret_metadata(self):
        package, runtime = fixture()
        plain, evidence = P.prepare(package, runtime_loader=runtime)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "private"
            first = P.write_private_bundle(package, plain, evidence, root=root)
            second = P.write_private_bundle(package, plain, evidence, root=root)
            self.assertNotEqual(first, second)
            self.assertEqual(package, (first / "input.package").read_bytes())
            self.assertEqual(SOURCE, (first / "source.py").read_bytes())
            document = json.loads((first / "evidence.json").read_bytes())
            self.assertEqual(evidence, document)
            self.assertNotIn("payload.base64", json.dumps(document))
            self.assertNotIn("nonce", json.dumps(document))
            self.assertNotIn("master", json.dumps(document))

    def test_cli_requires_authorization_before_reading_or_writing(self):
        with patch.object(Path, "open") as opened, self.assertRaisesRegex(
                P.InputFailure, "^PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED$"):
            P.main(["--input", "does-not-exist.txt"])
        opened.assert_not_called()

    def test_runtime_hash_mismatch_prevents_runtime_import(self):
        with patch.object(P, "digest", return_value="0" * 64), \
                patch.object(P.importlib.util, "spec_from_file_location") as imported, \
                self.assertRaisesRegex(P.InputFailure, "^PLUGIN_RUNTIME_IDENTITY_MISMATCH$"):
            P.load_runtime()
        imported.assert_not_called()

    def test_worker_returns_only_metadata_and_keeps_plaintext_off_stdout(self):
        with tempfile.TemporaryDirectory() as temporary:
            completed = subprocess.run(
                [sys.executable, "-I", str(P.ROOT / "scripts/prepare_plugin_source.py"),
                 "--worker", "--authorized"], input=SOURCE, cwd=temporary,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=True)
            reply = json.loads(completed.stdout)
            self.assertEqual("prepared", reply["status"])
            self.assertEqual(SOURCE, (Path(temporary) / "source.py").read_bytes())
            self.assertNotIn(b"from base.spider", completed.stdout)
            self.assertFalse(reply["evidence"]["sourceExecuted"])

    def test_worker_rejection_has_no_plaintext_file_or_input_echo(self):
        with tempfile.TemporaryDirectory() as temporary:
            completed = subprocess.run(
                [sys.executable, "-I", str(P.ROOT / "scripts/prepare_plugin_source.py"),
                 "--worker", "--authorized"], input=b"PRIVATE_INVALID_SOURCE(",
                cwd=temporary, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=True)
            self.assertEqual({"status": "rejected", "code": "PLUGIN_PLAINTEXT_SYNTAX_INVALID"},
                             json.loads(completed.stdout))
            self.assertFalse((Path(temporary) / "source.py").exists())
            self.assertNotIn(b"PRIVATE", completed.stdout + completed.stderr)


if __name__ == "__main__":
    unittest.main()
