import json
import tempfile
import unittest
from pathlib import Path

import at_source_projection as projection
import freeze_at_profile
import verify_at_binding


SOURCE = (
    "import json\n"
    "from base.spider import Spider\n"
    "PLUGIN_RUNTIME_REVISION = 'fixture-1'\n"
    "class Spider(Spider):\n"
    "    def init(self, extend=''):\n"
    "        return {'name': 'fixture'}\n"
)


class VerifyAtBindingTest(unittest.TestCase):
    def _sample(self, root: Path) -> Path:
        sample = root / "sample"
        sample.mkdir()
        source = SOURCE.encode()
        (sample / "source.py").write_bytes(source)
        manifest = {
            "schemaVersion": 1,
            "sourcePath": "/static/plugins/Fixture.py",
            "source": {"status": 200, "bytes": len(source),
                       "sha256": verify_at_binding._sha(source)},
            "sourceExecuted": False,
            "productionMutated": False,
            "productionImageChanged": False,
            "productionWriteRequests": 0,
        }
        (sample / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        projection.project(sample / "source.py", sample / "manifest.json",
                           sample / "projection.json")
        freeze_at_profile.freeze(sample / "projection.json", sample / "frozen-profile.json",
                                 ["S06", "R04", "R05"])
        return sample

    def test_exact_source_projection_and_profile_are_bound_without_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            sample = self._sample(Path(temp))
            result = verify_at_binding.verify(sample, sample / "binding.json")
            self.assertTrue(result["sourceRuleBindingVerified"])
            self.assertTrue(result["dependenciesVerified"])
            self.assertFalse(result["runtimeBindingVerified"])
            self.assertFalse(result["formalR05Eligible"])

    def test_tampered_projection_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            sample = self._sample(Path(temp))
            value = json.loads((sample / "projection.json").read_text(encoding="utf-8"))
            value["publicSourceData"]["dependencySha256"] = "0" * 64
            (sample / "projection.json").write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "AT_BINDING_DIGEST_MISMATCH"):
                verify_at_binding.verify(sample, sample / "binding.json")

    def test_configuration_replacement_cannot_be_validated_by_replacing_both_outputs(self):
        with tempfile.TemporaryDirectory() as temp:
            sample = self._sample(Path(temp))
            for filename in ("projection.json", "frozen-profile.json"):
                path = sample / filename
                value = json.loads(path.read_text(encoding="utf-8"))
                value["sourceConfig"] = {"gateway_timeout": 30}
                path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "AT_BINDING_SOURCE_CONFIG_MISMATCH"):
                verify_at_binding.verify(sample, sample / "binding.json")
            self.assertFalse((sample / "binding.json").exists())

    def test_omitted_empty_config_cannot_enable_runtime_projection_fallback(self):
        with tempfile.TemporaryDirectory() as temp:
            sample = self._sample(Path(temp))
            for filename in ("frozen-profile.json", "projection.json"):
                path = sample / filename
                value = json.loads(path.read_text(encoding="utf-8"))
                del value["sourceConfig"]
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "AT_BINDING_PROFILE_MISMATCH"):
                    verify_at_binding.verify(sample, sample / "binding.json")

    def test_manifest_configuration_survives_projection_freezing_and_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            sample = self._sample(Path(temp))
            path = sample / "manifest.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["sourceConfig"] = {"gateway_timeout": 30, "subtitle_enabled": False}
            path.write_text(json.dumps(value), encoding="utf-8")
            projection.project(sample / "source.py", path, sample / "projection.json")
            frozen = freeze_at_profile.freeze(sample / "projection.json", sample / "frozen-profile.json",
                                              ["S06", "R04", "R05"])
            result = verify_at_binding.verify(sample, sample / "binding.json")
            self.assertEqual(value["sourceConfig"], frozen["profile"]["sourceConfig"])
            self.assertEqual(verify_at_binding._digest(value["sourceConfig"]), result["sourceConfigSha256"])
            self.assertTrue(result["sourceConfigPresent"])


if __name__ == "__main__":
    unittest.main()
