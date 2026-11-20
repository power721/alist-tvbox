"""Read one authorized AT Python plugin without changing the AT instance.

The collector deliberately keeps the production boundary read-only: it only
issues GET requests to ``/api/plugins`` and the selected same-origin source
path.  The source is parsed with ``ast`` but never imported or executed.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import time
from urllib.parse import urljoin, urlsplit

import requests

import at_source_projection as projection


MAX_SOURCE_BYTES = 1_048_576
SENSITIVE_NAMES = {
    "password",
    "passwd",
    "token",
    "secret",
    "api_key",
    "apikey",
    "authorization",
    "cookie",
}


def _origin(value: str) -> tuple[str, str, int]:
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("AT_ORIGIN_INVALID")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("AT_ORIGIN_INVALID")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    return parsed.scheme.lower(), parsed.hostname.lower(), port


def _same_origin(left: str, right: str) -> bool:
    return _origin(left) == _origin(right)


def _headers() -> dict[str, str]:
    mode = os.environ.get("MAINTENANCE_AT_AUTH_MODE", "session")
    if mode == "api_key":
        value = os.environ.get("MAINTENANCE_AT_API_KEY", "")
        if not value:
            raise RuntimeError("AT_API_KEY_UNSET")
        return {"Accept": "application/json", "X-API-KEY": value}
    if mode == "session":
        value = os.environ.get("MAINTENANCE_AT_SESSION_TOKEN", "")
        if not value:
            raise RuntimeError("AT_SESSION_TOKEN_UNSET")
        return {"Accept": "application/json", "Authorization": value}
    raise RuntimeError("AT_AUTH_MODE_INVALID")


def _get(session: requests.Session, url: str, headers: dict[str, str],
         *, limit: int) -> tuple[bytes, str]:
    with session.get(
        url,
        headers=headers,
        timeout=(5, 20),
        allow_redirects=False,
        stream=True,
    ) as response:
        if 300 <= response.status_code < 400:
            raise RuntimeError("AT_REDIRECT_REFUSED")
        if response.status_code != 200:
            raise RuntimeError(f"AT_HTTP_{response.status_code}")
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        body = bytearray()
        for chunk in response.iter_content(16_384):
            body.extend(chunk)
            if len(body) > limit:
                raise RuntimeError("AT_RESPONSE_TOO_LARGE")
        return bytes(body), content_type


def _literal_credentials(tree: ast.AST) -> list[str]:
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) and node.value.value:
            for target in node.targets:
                name = (
                    target.id
                    if isinstance(target, ast.Name)
                    else target.attr
                    if isinstance(target, ast.Attribute)
                    else ""
                )
                lowered = name.lower()
                if lowered in SENSITIVE_NAMES or any(lowered.endswith("_" + key) for key in SENSITIVE_NAMES):
                    found.add(name)
        if isinstance(node, ast.Dict):
            for key, value in zip(node.keys, node.values):
                if (
                    isinstance(key, ast.Constant)
                    and str(key.value).lower() in SENSITIVE_NAMES
                    and isinstance(value, ast.Constant)
                    and value.value
                ):
                    found.add(str(key.value))
    return sorted(found)


def _signals(body: bytes, tree: ast.AST) -> dict[str, object]:
    lowered = body.lower()
    methods = sorted({
        node.name
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    })
    imports = sorted({
        alias.name.split(".", 1)[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    } | {
        node.module.split(".", 1)[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module
    })
    return {
        "topLevelClasses": sorted(node.name for node in tree.body if isinstance(node, ast.ClassDef)),
        "methodCount": len(methods),
        "methods": methods,
        "imports": imports,
        "hasProxyBase": b"proxy_base" in lowered,
        "hasCookie": b"cookie" in lowered,
        "hasSession": b"session" in lowered,
        "hasDataField": b'"data"' in body or b"'data'" in body,
        "hasSelectorCall": any(marker in body for marker in (b".select(", b"select_one(", b"cssselect(")),
    }


def collect(plugin_id: int, output: Path) -> dict[str, object]:
    origin = os.environ.get("MAINTENANCE_AT_ORIGIN", "").rstrip("/")
    if not origin:
        raise RuntimeError("AT_ORIGIN_UNSET")
    _origin(origin)
    headers = _headers()
    session = requests.Session()
    session.trust_env = False
    metadata_url = origin + "/api/plugins"
    metadata_body, metadata_type = _get(session, metadata_url, headers, limit=2_097_152)
    try:
        metadata = json.loads(metadata_body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RuntimeError("AT_METADATA_INVALID") from None
    if not isinstance(metadata, list) or len(metadata) > 5000:
        raise RuntimeError("AT_METADATA_INVALID")
    plugin = next(
        (
            row for row in metadata
            if isinstance(row, dict)
            and row.get("id") == plugin_id
            and row.get("enabled") is True
            and row.get("t4Visible") is True
        ),
        None,
    )
    if plugin is None:
        raise RuntimeError("AT_PLUGIN_NOT_ELIGIBLE")
    raw_extend = plugin.get("extend")
    if raw_extend in (None, ""):
        source_config: dict[str, object] = {}
    elif not isinstance(raw_extend, str):
        raise RuntimeError("AT_PLUGIN_EXT_INVALID")
    else:
        try:
            parsed_extend = json.loads(raw_extend)
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise RuntimeError("AT_PLUGIN_EXT_INVALID") from None
        try:
            source_config = projection.validate_source_config(parsed_extend)
        except ValueError:
            raise RuntimeError("AT_PLUGIN_EXT_INVALID") from None
    locator = plugin.get("url")
    if not isinstance(locator, str) or not locator:
        raise RuntimeError("AT_PLUGIN_URL_INVALID")
    source_url = urljoin(origin + "/", locator)
    if not _same_origin(origin, source_url):
        raise RuntimeError("AT_SOURCE_ORIGIN_MISMATCH")
    parsed = urlsplit(source_url)
    if (parsed.query or parsed.fragment or ".." in parsed.path or "%" in parsed.path
            or "\\" in parsed.path or not parsed.path.startswith("/static/")
            or not parsed.path.lower().endswith(".py")):
        raise RuntimeError("AT_SOURCE_URL_INVALID")
    first, source_type = _get(session, source_url, headers, limit=MAX_SOURCE_BYTES)
    time.sleep(1)
    second, _ = _get(session, source_url, headers, limit=MAX_SOURCE_BYTES)
    if first != second:
        raise RuntimeError("AT_SOURCE_CHANGED_DURING_READ")
    try:
        source_text = first.decode("utf-8-sig")
        tree = ast.parse(source_text)
    except (UnicodeDecodeError, SyntaxError):
        raise RuntimeError("AT_SOURCE_SYNTAX_INVALID") from None
    sensitive = _literal_credentials(tree)
    if sensitive:
        raise RuntimeError("AT_LITERAL_CREDENTIAL_REQUIRES_REVIEW")
    if output.exists():
        raise RuntimeError("AT_SAMPLE_OUTPUT_EXISTS")
    output.mkdir(parents=True)
    (output / "source.py").write_bytes(first)
    manifest = {
        "schemaVersion": 1,
        "observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "origin": origin,
        "instanceId": os.environ.get("MAINTENANCE_AT_INSTANCE_ID", ""),
        "pluginId": plugin_id,
        "name": str(plugin.get("name") or ""),
        "sourcePath": parsed.path,
        "metadata": {
            "status": 200,
            "contentType": metadata_type,
            "bytes": len(metadata_body),
            "sha256": hashlib.sha256(metadata_body).hexdigest(),
            "pluginCount": len(metadata),
        },
        "source": {
            "status": 200,
            "contentType": source_type,
            "bytes": len(first),
            "sha256": hashlib.sha256(first).hexdigest(),
            "stableAfterReads": 2,
            **_signals(first, tree),
        },
        "sourceConfig": source_config,
        "requestMethods": ["GET", "GET", "GET"],
        "productionMutated": False,
        "productionImageChanged": False,
        "productionWriteRequests": 0,
        "sourceExecuted": False,
        "connectionCredentialsStored": False,
        "credentialLiteralScan": "heuristic_no_obvious_literals",
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return {
        "evidenceDir": str(output),
        "pluginId": plugin_id,
        "name": manifest["name"],
        "sourceBytes": manifest["source"]["bytes"],
        "sourceSha256": manifest["source"]["sha256"],
        "metadataSha256": manifest["metadata"]["sha256"],
        "sourceStable": True,
        "productionMutated": False,
        "productionImageChanged": False,
        "productionWriteRequests": 0,
        "signals": {
            key: manifest["source"][key]
            for key in ("hasProxyBase", "hasCookie", "hasSession", "hasDataField", "hasSelectorCall")
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(collect(args.plugin_id, args.output), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
