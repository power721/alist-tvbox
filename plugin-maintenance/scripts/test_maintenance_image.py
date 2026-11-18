import unittest

import maintenance_image as image


class ImagePolicyTest(unittest.TestCase):
    def setUp(self):
        self.ident = "sha256:" + "a" * 64
        self.meta = image.identity("source", "0.59.0-SNAPSHOT", "b" * 64, "20261115t010000z")
        self.row = {"Id": self.ident, "RepoTags": [self.meta["tag"]],
                    "Config": {"Labels": self.meta["labels"]}}

    def test_versioned_name_and_required_labels(self):
        self.assertEqual(self.meta["tag"], "at-plugin-maintenance-source:v0.59.0-snapshot-20261115t010000z-bbbbbbbbbbbb")
        self.assertEqual(self.meta["labels"][image.LABEL + "project"], image.PROJECT)
        self.assertEqual(self.meta["labels"][image.LABEL + "revision"], "b" * 64)

    def test_build_always_applies_identity_without_changing_build_args(self):
        args = image.build_arguments(self.meta, "Dockerfile", ".", ["SOURCE_IMAGE=base:tag"])
        self.assertIn("--pull=false", args)
        self.assertIn("SOURCE_IMAGE=base:tag", args)
        self.assertIn(image.LABEL + "project=" + image.PROJECT, args)

    def test_current_rollback_and_runtime_are_protected(self):
        keep = image.keep_images({"current": [self.ident], "rollback": ["sha256:" + "b" * 64], "runtime": []})
        self.assertEqual(image.retirement_plan([self.row], "source", keep), [])

    def test_only_matching_project_role_is_eligible(self):
        self.assertEqual(len(image.retirement_plan([self.row], "source", set())), 1)
        self.assertEqual(image.retirement_plan([self.row], "http", set()), [])
        self.row["Config"]["Labels"] = {}
        self.assertEqual(image.retirement_plan([self.row], "source", set()), [])

    def test_foreign_alias_prevents_retirement(self):
        self.row["RepoTags"].append("other-project:latest")
        self.assertEqual(image.retirement_plan([self.row], "source", set()), [])

    def test_missing_current_manifest_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "KEEP_MANIFEST"):
            image.keep_images({"current": [], "rollback": [], "runtime": []})


if __name__ == "__main__":
    unittest.main()
