"""Create a credential-free public source projection from an authorized AT sample.

The source is parsed with :mod:`ast` only.  It is never imported, executed, or
sent to a remote runtime by this helper.  The resulting projection is suitable
for the managed loader's ``publicSourceData`` field and intentionally excludes
cookies, tokens, proxy addresses, authorization headers, and full EXT values.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
from typing import Any


SENSITIVE = (
    "token",
    "secret",
    "password",
    "cookie",
    "authorization",
    "proxy",
    "credential",
    "apikey",
)
SAFE_CONSTANTS = {
    "DEFAULT_HOST": "host",
    "DEFAULT_GATEWAY_ADAPTER": "gatewayAdapter",
    "DEFAULT_PAGE_TRANSPORT": "pageTransport",
    "PLUGIN_RUNTIME_REVISION": "runtimeRevision",
}
SOURCE_CONFIG_KEYS = {
    "gateway_timeout",
    "gateway_total_timeout",
    "gateway_retries",
    "detail_gateway_retries",
    "session_ttl_minutes",
}
STANDARD_METHODS = (
    "init",
    "homeContent",
    "categoryContent",
    "detailContent",
    "searchContent",
    "playerContent",
    "localProxy",
)


def _sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _literal_string(node: ast.AST) -> str | None:
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def _top_level_strings(tree: ast.Module) -> dict[str, str]:
    values: dict[str, str] = {}
    for node in tree.body:
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        value = _literal_string(node.value)
        if value is None:
            continue
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        for target in targets:
            if isinstance(target, ast.Name):
                values[target.id] = value
    return values


def _imports(tree: ast.AST) -> list[str]:
    result: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            result.update(alias.name.split(".", 1)[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            result.add(node.module.split(".", 1)[0])
    return sorted(result)


def _methods(tree: ast.AST) -> list[str]:
    return sorted({
        node.name
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    })


def _marker_count(source: bytes, marker: bytes) -> str:
    return str(source.lower().count(marker.lower()))


def _rule_summary(source: bytes, tree: ast.Module) -> dict[str, Any]:
    methods = _methods(tree)
    return {
        "standardMethods": [name for name in STANDARD_METHODS if name in methods],
        "methodCount": str(len(methods)),
        "hasProxyBase": str(b"proxy_base" in source.lower()).lower(),
        "hasCookie": str(b"cookie" in source.lower()).lower(),
        "hasSession": str(b"session" in source.lower()).lower(),
        "hasLocalProxy": str("localProxy" in methods).lower(),
        "httpCallMarkers": {
            "get": _marker_count(source, b".get("),
            "post": _marker_count(source, b".post("),
            "session": _marker_count(source, b"requests.Session"),
        },
    }


def _digest_json(value: Any) -> str:
    body = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()
    return _sha(body)


def validate_source_config(data: Any, *, allow_empty: bool = True) -> dict[str, Any]:
    """Validate the public JSON config stored in an AT plugin's ``extend``.

    This is deliberately separate from the AST projection: ``publicSourceData``
    binds rules/dependencies, while ``sourceConfig`` is the actual public
    plugin configuration sent to the source runtime.  Only bounded primitives
    and the explicitly approved public gateway URL are accepted.
    """
    if not isinstance(data, dict) or len(data) > 32 or (not allow_empty and not data):
        raise ValueError("AT_SOURCE_CONFIG_INVALID")
    reserved = {
        "api", "source", "loader", "raw", "data", "extend", "preheaturl",
        "t4playtype", "playbacksourcekind", "playbacksourcekey",
        "playbacksourcename", "playbackconfigurl", "proxybase",
    }
    result: dict[str, Any] = {}
    for key, value in data.items():
        if (not isinstance(key, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key)):
            raise ValueError("AT_SOURCE_CONFIG_INVALID")
        normalized = key.replace("_", "").lower()
        if normalized in reserved:
            raise ValueError("AT_SOURCE_CONFIG_INVALID")
        if key != "proxy_gateway" and any(part in normalized for part in (
                "token", "secret", "password", "cookie", "authorization", "proxy",
                "credential", "apikey")):
            raise ValueError("AT_SOURCE_CONFIG_INVALID")
        if key == "proxy_gateway":
            if (not isinstance(value, str) or not value.startswith(("http://", "https://"))
                    or any(marker in value for marker in ("@", "?", "#", "\r", "\n"))
                    or len(value) > 1024):
                raise ValueError("AT_SOURCE_CONFIG_INVALID")
        elif key in SOURCE_CONFIG_KEYS:
            if type(value) is not int or not 0 <= value <= 100000:
                raise ValueError("AT_SOURCE_CONFIG_INVALID")
        elif key == "subtitle_enabled":
            if type(value) is not bool:
                raise ValueError("AT_SOURCE_CONFIG_INVALID")
        elif (not isinstance(value, str) or len(value) > 1024
              or any(ord(char) < 32 or ord(char) == 127
                     or 0xD800 <= ord(char) <= 0xDFFF for char in value)):
            raise ValueError("AT_SOURCE_CONFIG_INVALID")
        result[key] = value
    if len(json.dumps(result, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()) > 8192:
        raise ValueError("AT_SOURCE_CONFIG_INVALID")
    return result


def _safe_projection(source: bytes, manifest: dict[str, Any], tree: ast.Module) -> dict[str, str]:
    constants = _top_level_strings(tree)
    data: dict[str, str] = {}
    for constant, key in SAFE_CONSTANTS.items():
        value = constants.get(constant)
        if value:
            data[key] = value

    source_meta = manifest.get("source")
    if not isinstance(source_meta, dict):
        raise ValueError("AT_MANIFEST_SOURCE_INVALID")
    data["sourcePath"] = str(manifest.get("sourcePath") or "")
    data["sourceRevision"] = _sha(source)

    rules = _rule_summary(source, tree)
    dependencies = {"imports": _imports(tree)}
    data["sourceRuleSha256"] = _digest_json(rules)
    data["dependencySha256"] = _digest_json(dependencies)
    data["ruleMethodCount"] = rules["methodCount"]

    for key, value in data.items():
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key):
            raise ValueError("AT_PUBLIC_DATA_KEY_INVALID")
        normalized = key.replace("_", "").lower()
        if any(part in normalized for part in SENSITIVE):
            raise ValueError("AT_PUBLIC_DATA_SENSITIVE_KEY")
        if not isinstance(value, str) or not value or len(value) > 1024:
            raise ValueError("AT_PUBLIC_DATA_VALUE_INVALID")
        if any(ord(char) < 32 or ord(char) == 127 or 0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise ValueError("AT_PUBLIC_DATA_VALUE_INVALID")
    if not 1 <= len(data) <= 32:
        raise ValueError("AT_PUBLIC_DATA_SIZE_INVALID")
    if len(json.dumps(data, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()) > 8192:
        raise ValueError("AT_PUBLIC_DATA_SIZE_INVALID")
    return data


def project(source_path: Path, manifest_path: Path, output_path: Path) -> dict[str, Any]:
    source = source_path.read_bytes()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = manifest.get("source", {}).get("sha256")
    if not isinstance(expected, str) or _sha(source) != expected:
        raise ValueError("AT_SOURCE_MANIFEST_SHA_MISMATCH")
    try:
        tree = ast.parse(source.decode("utf-8-sig"), filename=str(source_path))
    except (UnicodeDecodeError, SyntaxError):
        raise ValueError("AT_SOURCE_SYNTAX_INVALID") from None

    public_data = _safe_projection(source, manifest, tree)
    source_config = validate_source_config(manifest.get("sourceConfig", {}))
    result = {
        "schemaVersion": 1,
        "evidenceClass": "authorized_at_readonly_ast_projection",
        "sourceSha256": _sha(source),
        "sourceBytes": len(source),
        "sourceExecuted": False,
        "productionMutated": False,
        "productionImageChanged": False,
        "publicSourceData": public_data,
        "sourceConfig": source_config,
        "rules": _rule_summary(source, tree),
        "dependencies": {"imports": _imports(tree)},
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = project(args.source, args.manifest, args.output)
    print(json.dumps({
        "output": str(args.output),
        "sourceSha256": result["sourceSha256"],
        "publicKeys": sorted(result["publicSourceData"]),
        "sourceExecuted": result["sourceExecuted"],
        "productionMutated": result["productionMutated"],
    }, ensure_ascii=True))


if __name__ == "__main__":
    main()
