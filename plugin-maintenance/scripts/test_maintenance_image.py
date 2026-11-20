import unittest
from unittest.mock import patch

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

    def embedded_fixture(self):
        meta = image.identity("embedded", "0.106.0-rc29", "b" * 64)
        row = {"Id": self.ident, "RepoTags": [meta["tag"]], "Config": {"Labels": meta["labels"]}}
        container = {"Id": "c" * 64, "Image": self.ident, "Name": "/at-maintenance-embedded-rc10629",
                     "Config": {"Labels": meta["labels"]},
                     "State": {"Status": "exited", "Running": False, "Restarting": False},
                     "Mounts": [{"Type": "bind", "Destination": "/data", "Source": "/lab/rc29/data"}]}
        return row, container

    def test_only_stopped_owned_embedded_instances_with_external_data_are_retired(self):
        row, container = self.embedded_fixture()
        plan = image.stopped_container_plan([container], [row], "embedded", set())
        self.assertEqual(plan[0]["preservedDataPath"], "/lab/rc29/data")
        self.assertEqual(image.stopped_container_plan([container], [row], "embedded", {self.ident}), [])
        for field, value in (("Status", "running"), ("Running", True), ("Restarting", True)):
            changed = {**container, "State": {**container["State"], field: value}}
            self.assertEqual(image.stopped_container_plan([changed], [row], "embedded", set()), [])
        for changed in ({**container, "Mounts": []}, {**container, "Config": {"Labels": {}}},
                        {**container, "Id": "short"}, {**container, "Mounts": [
                            {"Type": "volume", "Destination": "/data", "Source": "/volumes/data"}]}):
            self.assertEqual(image.stopped_container_plan([changed], [row], "embedded", set()), [])

    def test_explicit_retirement_preserves_volumes_and_rechecks_container_state(self):
        import json
        row, container = self.embedded_fixture()
        keep = {"current": ["sha256:" + "d" * 64], "rollback": [], "runtime": []}
        for restarted in (False, True):
            state = {**container, "State": {"Status": "running", "Running": True, "Restarting": False}}
            commands = []

            def docker(args):
                commands.append(args)
                if args[:2] == ["image", "ls"]:
                    return self.ident
                if args[:2] == ["image", "inspect"]:
                    return json.dumps([row])
                if args[:2] == ["container", "inspect"]:
                    return json.dumps(state if restarted else container)
                return ""

            after = [state] if restarted else []
            with patch.object(image, "container_inventory", side_effect=[[container], after]), \
                    patch.object(image, "docker", side_effect=docker):
                result = image.retire("embedded", keep, apply=True, retire_stopped_containers=True)
            if restarted:
                self.assertEqual(result["containerResults"][0]["status"], "SKIPPED_CHANGED")
                self.assertEqual(result["candidates"], [])
                self.assertFalse(any(args[:2] == ["container", "rm"] for args in commands))
            else:
                self.assertIn(["container", "rm", container["Id"]], commands)
                self.assertEqual(result["results"][0]["status"], "DELETED")
            self.assertFalse(result["volumesDeleted"])
            self.assertFalse(any(flag in args for args in commands for flag in ("--force", "--volumes", "-v")))

    def test_dry_run_and_default_retirement_never_remove_containers(self):
        import json
        row, container = self.embedded_fixture()
        keep = {"current": ["sha256:" + "d" * 64], "rollback": [], "runtime": []}
        for enabled in (False, True):
            with patch.object(image, "container_inventory", return_value=[container]), \
                    patch.object(image, "docker", side_effect=[self.ident, json.dumps([row])]) as docker:
                result = image.retire("embedded", keep, retire_stopped_containers=enabled)
            self.assertEqual(len(result["containerCandidates"]), int(enabled))
            self.assertEqual(len(result["candidates"]), int(enabled))
            self.assertEqual(result["containerResults"], [])
            self.assertFalse(any(call.args[0][:2] == ["container", "rm"] for call in docker.call_args_list))


if __name__ == "__main__":
    unittest.main()
