"""Exercise current native and managed home contracts over their actual JSONL worker."""
import base64
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import unittest


MODULE = Path(__file__).resolve().parents[1]
REPO = MODULE.parent
FIXTURE = MODULE / "at-contract-lab/fixtures/t4_home.py"
PROGRAM = r'''
import importlib.util,pathlib,runpy,socket,sys,requests
root=pathlib.Path(sys.argv[1]); mode=sys.argv[2]
def blocked(*args,**kwargs):raise RuntimeError("NETWORK_DISABLED_IN_FIXTURE")
socket.socket.connect=socket.create_connection=socket.getaddrinfo=blocked
requests.sessions.Session.request=blocked
runtime=(root/"src/main/resources/scripts" if mode=="native" else
         root/"plugin-maintenance/server/src/main/resources/worker/runtime")
atvp=(root/"src/main/resources/static/Atvp.py" if mode=="native" else runtime/"Atvp.py")
sys.path.insert(0,str(runtime))
sys.argv=[str(runtime/"t4worker.py"),str(atvp)]
worker=runpy.run_path(sys.argv[0],run_name="trusted_current_home_fixture")["main"].__globals__
if mode=="managed":
 spec=importlib.util.spec_from_file_location("trusted_home_adapter",root/"plugin-maintenance/managed-source/egress_payload.py")
 adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
 adapter.install_home_merge(worker)
 adapter.install_session_proxy(worker)
worker["main"]()
'''


class Worker:
    def __init__(self, directory, mode, source, options):
        self.replies = queue.Queue()
        self.ordinal = 0
        self.log = (directory / "worker.log").open("wb")
        environment = {key: value for key, value in os.environ.items()
                       if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
        environment["ATVP_CACHE_FILE"] = str(directory / "cache.json")
        self.process = subprocess.Popen(
            [sys.executable, "-I", "-B", "-c", PROGRAM, str(REPO), mode],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, env=environment)

        def read():
            try:
                for line in self.process.stdout:
                    self.replies.put(json.loads(line.decode("utf-8")))
            finally:
                self.replies.put(None)

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        payload = {"source": str(source), "raw": True, "data": options}
        try:
            self.call("init", {"extend": base64.b64encode(json.dumps(payload).encode()).decode()})
        except BaseException:
            self.close()
            raise

    def reply(self, method, params=None):
        self.ordinal += 1
        ident = str(self.ordinal)
        self.process.stdin.write((json.dumps({"id": ident, "method": method,
                                              "params": params or {}}) + "\n").encode())
        self.process.stdin.flush()
        value = self.replies.get(timeout=15)
        if value is None or value.get("id") != ident:
            raise AssertionError("Worker JSONL response missing or mismatched")
        return value

    def call(self, method, params=None):
        value = self.reply(method, params)
        if value["ok"] is not True:
            raise AssertionError(value.get("error"))
        return value["result"]

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.reader.join(timeout=5)
        self.process.stdout.close()
        self.log.close()


class HomeRuntimeTest(unittest.TestCase):
    def worker(self, mode, scenario="replace", backend_parse=False, source=None):
        directory = tempfile.TemporaryDirectory(prefix="current-t4-home-")
        self.addCleanup(directory.cleanup)
        worker = Worker(Path(directory.name), mode, source or FIXTURE,
                        {"homeScenario": scenario, "backendParse": backend_parse})
        self.addCleanup(worker.close)
        return worker

    def test_recommendations_replace_list_preserving_metadata_order_duplicates_and_filter(self):
        for mode in ("native", "managed"):
            with self.subTest(mode=mode):
                worker = self.worker(mode)
                for filtered in (True, False):
                    value = worker.call("home", {"filter": filtered})
                    self.assertEqual(["rec", "rec"], [row["vod_id"] for row in value["list"]])
                    self.assertEqual(["First", "Second"], [row["vod_name"] for row in value["list"]])
                    self.assertEqual({"keep": True}, value["home_metadata"])
                    self.assertEqual("lab", value["class"][0]["type_id"])
                    self.assertIn("lab", value["filters"])
                    self.assertNotIn("ignored_video_metadata", value)
                self.assertEqual(["home:True", "video", "home:False", "video"],
                                 worker.call("category", {"t": "lab"})["lab_events"])
                self.assertEqual({"status": "closed"}, worker.call("destroy"))

    def test_optional_null_or_missing_recommendation_list_keeps_home(self):
        for mode in ("native", "managed"):
            for scenario in ("null_video", "no_list", "null_video_list"):
                with self.subTest(mode=mode, scenario=scenario):
                    worker = self.worker(mode, scenario)
                    self.assertEqual("base", worker.call("home")["list"][0]["vod_id"])

    def test_missing_and_inherited_default_recommendation_are_optional(self):
        directory = tempfile.TemporaryDirectory(prefix="optional-t4-home-")
        self.addCleanup(directory.cleanup)
        for kind in ("plain", "inherited"):
            source = Path(directory.name) / (kind + ".py")
            declaration = ("from base.spider import Spider as Base\nclass Spider(Base):\n"
                           if kind == "inherited" else "class Spider:\n")
            source.write_text(declaration +
                              " def init(self,extend=''):pass\n"
                              " def homeContent(self,filter):return {'class':[]}\n", encoding="utf-8")
            for mode in ("native", "managed"):
                with self.subTest(mode=mode, kind=kind):
                    self.assertEqual({"class": [], "list": []}, self.worker(mode, source=source).call("home"))

    def test_explicit_empty_recommendation_clears_existing_list(self):
        for mode in ("native", "managed"):
            with self.subTest(mode=mode):
                value = self.worker(mode, "empty_video").call("home")
                self.assertEqual([], value["list"])
                self.assertEqual({"keep": True}, value["home_metadata"])

    def test_null_home_can_supply_recommendations_without_inventing_categories(self):
        for mode in ("native", "managed"):
            with self.subTest(mode=mode):
                value = self.worker(mode, "null_home").call("home")
                self.assertEqual(["list"], list(value))
                self.assertEqual(2, len(value["list"]))

    def test_json_and_object_cards_normalize_once_in_both_backend_modes(self):
        for mode in ("native", "managed"):
            for scenario in ("replace", "json"):
                for parsed in (True, False):
                    with self.subTest(mode=mode, scenario=scenario, parsed=parsed):
                        value = self.worker(mode, scenario, parsed).call("home")
                        for card in value["list"]:
                            self.assertEqual("atvp_detail:rec" if parsed else "rec", card["vod_id"])
                            self.assertEqual("folder" if parsed else None, card.get("vod_tag"))
                            self.assertEqual("https://example.invalid/rec.jpg", card["vod_pic"])

    def test_invalid_returns_fail_instead_of_becoming_successful_empty_lists(self):
        for mode in ("native", "managed"):
            for scenario in ("invalid_home", "invalid_home_list", "invalid_video",
                             "invalid_video_list", "invalid_video_json"):
                with self.subTest(mode=mode, scenario=scenario):
                    worker = self.worker(mode, scenario)
                    reply = worker.reply("home")
                    self.assertFalse(reply["ok"])
                    self.assertNotIn("result", reply)
                    self.assertEqual({"status": "ready"}, worker.call("ping"))

    def test_callback_errors_preserve_jsonl_failure_and_do_not_retry(self):
        for mode in ("native", "managed"):
            for scenario, events in (("home_error", ["home:True"]),
                                     ("video_error", ["home:True", "video"])):
                with self.subTest(mode=mode, scenario=scenario):
                    worker = self.worker(mode, scenario)
                    reply = worker.reply("home")
                    self.assertFalse(reply["ok"])
                    self.assertEqual("ValueError", reply["error"]["code"])
                    self.assertEqual(events, worker.call("category", {"t": "lab"})["lab_events"])


if __name__ == "__main__":
    unittest.main()
