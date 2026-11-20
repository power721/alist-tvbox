"""Freeze a consumable managed-loader profile from a read-only AT projection.

The input projection is AST-derived and must explicitly prove that the source
was not executed and production was not mutated.  The output is the exact
profile JSON accepted by ``managed_loader.Loader``; it contains only bounded
public source data and caller-selected required nodes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

import at_source_projection as source_projection


NODE_RE = re.compile(r"^(?:S0[1-8]|C(?:0[1-9]|1[01])|R(?:0[1-9]|10)|M(?:0[1-9]|10)|P0[1-5])$")
SHA_RE = re.compile(r"^[a-f0-9]{64}$")


def digest(value: object) -> str:
    return hashlib.sha256(json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")).hexdigest()


def freeze(projection_path: Path, output_path: Path, required_nodes: list[str]) -> dict[str, object]:
    projection = json.loads(projection_path.read_text(encoding="utf-8"))
    if projection.get("schemaVersion") != 1 or projection.get(
            "evidenceClass") != "authorized_at_readonly_ast_projection":
        raise ValueError("AT_PROJECTION_IDENTITY_INVALID")
    if projection.get("sourceExecuted") is not False or projection.get("productionMutated") is not False:
        raise ValueError("AT_PROJECTION_NOT_READ_ONLY")
    source_sha = projection.get("sourceSha256")
    if not isinstance(source_sha, str) or not SHA_RE.fullmatch(source_sha):
        raise ValueError("AT_PROJECTION_SOURCE_SHA_INVALID")
    public = projection.get("publicSourceData")
    if not isinstance(public, dict) or public.get("sourceRevision") != source_sha:
        raise ValueError("AT_PROJECTION_PUBLIC_DATA_INVALID")
    for key in ("sourceRuleSha256", "dependencySha256"):
        if not isinstance(public.get(key), str) or not SHA_RE.fullmatch(public[key]):
            raise ValueError("AT_PROJECTION_PUBLIC_DATA_INVALID")
    try:
        source_config = source_projection.validate_source_config(projection.get("sourceConfig", {}))
    except ValueError:
        raise ValueError("AT_PROJECTION_SOURCE_CONFIG_INVALID") from None
    if not required_nodes or len(set(required_nodes)) != len(required_nodes):
        raise ValueError("AT_PROFILE_NODES_INVALID")
    if any(not isinstance(node, str) or not NODE_RE.fullmatch(node) for node in required_nodes):
        raise ValueError("AT_PROFILE_NODES_INVALID")
    runtime_revision = public.get("runtimeRevision")
    if not isinstance(runtime_revision, str) or not runtime_revision:
        raise ValueError("AT_PROFILE_RUNTIME_REVISION_MISSING")
    profile = {
        "schemaVersion": 1,
        "runtime": "t4",
        "runtimeRevision": runtime_revision,
        "requiredNodes": list(required_nodes),
        "publicSourceData": public,
        "sourceConfig": source_config,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(profile, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    return {
        "profile": profile,
        "sourceSha256": source_sha,
        "profileSha256": digest(profile),
        "requiredNodes": list(required_nodes),
        "sourceExecuted": False,
        "productionMutated": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--projection", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--required-node", action="append", dest="required_nodes", required=True)
    args = parser.parse_args()
    result = freeze(args.projection, args.output, args.required_nodes)
    print(json.dumps({key: value for key, value in result.items() if key != "profile"},
                     ensure_ascii=True))


if __name__ == "__main__":
    main()
