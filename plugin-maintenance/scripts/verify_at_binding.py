"""Verify a read-only AT sample's static source/profile binding.

This command never imports or executes the sampled Spider.  It proves only
that the exact source bytes, AST-derived rule/dependency digests, projection,
and frozen profile agree.  Runtime installation, real isolation, and media
playback remain separate evidence classes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

import at_source_projection as projection


SHA_RE = re.compile(r"^[a-f0-9]{64}$")


def _sha(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def _digest(value: object) -> str:
    return _sha(json.dumps(value, ensure_ascii=True, sort_keys=True,
                           separators=(",", ":")).encode("utf-8"))


def _read_json(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ValueError("AT_BINDING_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("AT_BINDING_JSON_INVALID")
    return value


def verify(sample_dir: Path, output: Path) -> dict[str, object]:
    sample_dir = sample_dir.resolve(strict=True)
    if not sample_dir.is_dir():
        raise ValueError("AT_BINDING_SAMPLE_INVALID")
    source_path = sample_dir / "source.py"
    manifest_path = sample_dir / "manifest.json"
    projection_path = sample_dir / "projection.json"
    profile_path = sample_dir / "frozen-profile.json"
    source = source_path.read_bytes()
    manifest = _read_json(manifest_path)
    projected = _read_json(projection_path)
    profile = _read_json(profile_path)

    source_meta = manifest.get("source")
    if not isinstance(source_meta, dict) or source_meta.get("status") != 200:
        raise ValueError("AT_BINDING_MANIFEST_SOURCE_INVALID")
    source_sha = _sha(source)
    if source_meta.get("sha256") != source_sha or source_meta.get("bytes") != len(source):
        raise ValueError("AT_BINDING_SOURCE_SHA_MISMATCH")
    if (manifest.get("sourceExecuted") is not False
            or manifest.get("productionMutated") is not False
            or manifest.get("productionImageChanged") is not False
            or manifest.get("productionWriteRequests") != 0):
        raise ValueError("AT_BINDING_READ_ONLY_ASSERTION_INVALID")

    try:
        tree = projection.ast.parse(source.decode("utf-8-sig"), filename=str(source_path))
    except (UnicodeDecodeError, SyntaxError):
        raise ValueError("AT_BINDING_SOURCE_SYNTAX_INVALID") from None
    rules = projection._rule_summary(source, tree)
    dependencies = {"imports": projection._imports(tree)}
    source_rule_sha = projection._digest_json(rules)
    dependency_sha = projection._digest_json(dependencies)

    if (projected.get("schemaVersion") != 1
            or projected.get("evidenceClass") != "authorized_at_readonly_ast_projection"
            or projected.get("sourceExecuted") is not False
            or projected.get("productionMutated") is not False
            or projected.get("sourceSha256") != source_sha
            or projected.get("rules") != rules
            or projected.get("dependencies") != dependencies):
        raise ValueError("AT_BINDING_PROJECTION_MISMATCH")
    public = projected.get("publicSourceData")
    if (not isinstance(public, dict)
            or public.get("sourceRevision") != source_sha
            or public.get("sourcePath") != manifest.get("sourcePath")):
        raise ValueError("AT_BINDING_PUBLIC_DATA_MISMATCH")
    if (public.get("sourceRuleSha256") != source_rule_sha
            or public.get("dependencySha256") != dependency_sha):
        raise ValueError("AT_BINDING_DIGEST_MISMATCH")
    source_config = projected.get("sourceConfig", {})
    try:
        source_config = projection.validate_source_config(source_config)
        manifest_config = projection.validate_source_config(manifest.get("sourceConfig", {}))
    except ValueError:
        raise ValueError("AT_BINDING_SOURCE_CONFIG_INVALID") from None
    if source_config != manifest_config:
        raise ValueError("AT_BINDING_SOURCE_CONFIG_MISMATCH")

    if (profile.get("schemaVersion") != 1 or profile.get("runtime") != "t4"
            or not isinstance(profile.get("requiredNodes"), list)
            or profile.get("requiredNodes") != ["S06", "R04", "R05"]
            or profile.get("publicSourceData") != public
            or "sourceConfig" not in projected or "sourceConfig" not in profile
            or profile.get("sourceConfig", {}) != source_config):
        raise ValueError("AT_BINDING_PROFILE_MISMATCH")
    profile_sha = _digest(profile)
    output_value = {
        "schemaVersion": 1,
        "evidenceClass": "authorized_at_readonly_static_binding",
        "verificationScope": "exact_source_bytes_ast_projection_and_frozen_profile",
        "sourceSha256": source_sha,
        "sourceBytes": len(source),
        "sourceRuleSha256": source_rule_sha,
        "dependencySha256": dependency_sha,
        "sourceConfigSha256": _digest(source_config),
        "sourceConfigPresent": "sourceConfig" in profile,
        "profileSha256": profile_sha,
        "sourceRuleBindingVerified": True,
        "dependenciesVerified": True,
        "runtimeBindingVerified": False,
        "runtimeBindingReason": "AT_RUNTIME_REVISION_DECLARATION_ONLY",
        "sourceExecuted": False,
        "productionMutated": False,
        "productionImageChanged": False,
        "productionWriteRequests": 0,
        "formalR05Eligible": False,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(output_value, ensure_ascii=True, indent=2) + "\n",
                      encoding="utf-8")
    return output_value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.sample_dir, args.output), ensure_ascii=True))


if __name__ == "__main__":
    main()
