"""Only the trusted adapter is imported. No submitted Spider or public network is executed."""
import base64
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest


class ProxyPayloadTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / "managed-source/egress_payload.py"
        spec = importlib.util.spec_from_file_location("trusted_proxy_adapter_test", path)
        self.payload = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.payload)
        self.calls = []
        self.player = {"parse": 0, "url": "http://127.0.0.1:9978/proxy?do=py&siteKey=fixture&type=video&id=a%2Bb"}
        self.body = bytes([0x47]) + bytes(187)
        self.local = [206, "video/mp2t", self.body, {
            "Content-Range": "bytes 0-187/188", "Accept-Ranges": "bytes", "Set-Cookie": "never-forward"}]
        def handle(method, params):
            self.calls.append((method, params))
            return dict(self.player) if method == "play" else self.local
        self.worker = {"handle": handle, "_quiet_stdout": contextlib.nullcontext}
        self.payload.install_session_proxy(self.worker)

    def proxy(self, *, target="/local?type=video&id=a%2Bb", head=False):
        return self.worker["handle"]("proxy", {"target": target, "range": "bytes=0-32767", "head": head})

    def test_player_placeholder_and_proxy_use_existing_local_handler_with_exact_query_and_bytes(self):
        result = self.worker["handle"]("play", {"proxy_base": "http://managed.invalid"})
        self.assertEqual("http://managed.invalid/local?type=video&id=a%2Bb", result["url"])
        body = self.proxy()
        self.assertEqual(("local", {"type": "video", "id": "a+b", "headers": {"Range": "bytes=0-32767"}}),
                         self.calls[-1])
        self.assertEqual(self.body, base64.b64decode(body["body"], validate=True))
        self.assertEqual(hashlib.sha256(self.body).hexdigest(), body["sha256"])
        self.assertEqual({"content-range": "bytes 0-187/188", "accept-ranges": "bytes"}, body["headers"])

    def test_head_retains_representation_length_without_delivering_bytes(self):
        value = self.proxy(head=True)
        self.assertEqual(188, value["bytes"])
        self.assertEqual("", value["body"])
        self.assertEqual(hashlib.sha256(b"").hexdigest(), value["sha256"])
        self.assertEqual(1, len(self.calls))

    def test_bounded_manifest_rewrites_only_uri_lines_and_keeps_complete_body(self):
        manifest = ("#EXTM3U\r\n#EXT-X-VERSION:3\r\n#EXTINF:5,\r\n"
                    "http://127.0.0.1:9978/proxy?do=py&siteKey=fixture&type=segment&id=a%2Bb\r\n")
        self.local = [200, "application/vnd.apple.mpegurl", manifest]
        for head in (False, True):
            for requested_range in ("", "bytes=0-32767"):
                value = self.worker["handle"]("proxy", {
                    "target": "/local?type=m3u8", "range": requested_range, "head": head})
                expected = manifest.replace(
                    "http://127.0.0.1:9978/proxy?do=py&siteKey=fixture&", "http://managed.invalid/local?")
                self.assertEqual(200, value["status"])
                self.assertNotIn("content-range", value["headers"])
                self.assertEqual(len(expected.encode()), value["bytes"])
                self.assertEqual(b"" if head else expected.encode(), base64.b64decode(value["body"], validate=True))
        self.assertEqual(4, len(self.calls))
        manifest = "#EXTM3U\n" + ("#EXTINF:5,\nhttp://127.0.0.1:9978/proxy?do=py&type=segment&id=a\n" * 1800)
        self.local = [200, "application/vnd.apple.mpegurl", manifest]
        result = self.worker["handle"]("proxy", {"target": "/local?type=m3u8", "range": "", "head": False})
        expected = manifest.replace("http://127.0.0.1:9978/proxy?do=py&", "http://managed.invalid/local?")
        self.assertGreater(result["bytes"], 98304)
        self.assertEqual(expected.encode(), base64.b64decode(result["body"], validate=True))
        self.assertEqual(hashlib.sha256(expected.encode()).hexdigest(), result["sha256"])

    def test_ignored_range_is_applied_to_the_decoded_local_segment_not_claimed_as_upstream_206(self):
        full = (bytes([0x47]) + bytes(187)) * 2000
        self.local = [200, "video/mp2t", full, {"Set-Cookie": "never-forward"}]
        for head in (False, True):
            value = self.proxy(head=head)
            body = b"" if head else full[:32768]
            self.assertEqual(206, value["status"])
            self.assertEqual(32768, value["bytes"])
            self.assertEqual({"accept-ranges": "bytes", "content-range": "bytes 0-32767/376000"},
                             value["headers"])
            self.assertEqual(body, base64.b64decode(value["body"], validate=True))
            self.assertEqual(hashlib.sha256(body).hexdigest(), value["sha256"])
        with self.assertRaises(ValueError):
            self.worker["handle"]("proxy", {"target": "/local?type=segment", "range": "", "head": False})

    def test_manifest_range_oversize_and_uri_expansion_are_never_truncated_to_fit(self):
        for manifest, range_value in (
                ("#EXTM3U\n#EXTINF:5,\n/local?type=segment\n", "bytes=1-32767"),
                ("#EXTM3U\n" + "#comment\n" * 65536, ""),
                ("#EXTM3U\n#EXTINF:5,\n" + (
                    "http://127.0.0.1:9978/proxy?do=py&id=" + "/" * 1000 + "\n") * 520, "")):
            self.local = [200, "application/vnd.apple.mpegurl", manifest]
            with self.subTest(range=range_value, length=len(manifest)), self.assertRaises(ValueError):
                self.worker["handle"]("proxy", {"target": "/local?type=m3u8",
                                               "range": range_value, "head": False})

    def test_other_local_ports_and_nonproxy_urls_are_not_silently_rewritten(self):
        for url in ("http://127.0.0.1:1234/video", "http://127.0.0.1:9978/proxy?do=other",
                    "https://media.example.invalid/segment.ts",
                    "http://user@127.0.0.1:9978/proxy?do=py&type=video",
                    "http://127.0.0.1:9978/proxy?do=py&type=video#fragment",
                    "http://127.0.0.1:9978/proxy?do=py&do=py"):
            self.player["url"] = url
            self.assertEqual(url, self.worker["handle"]("play", {"proxy_base": "http://managed.invalid"})["url"])

    def test_covers_rewrite_only_real_source_proxy_urls_without_changing_other_fields(self):
        original = {"list": [{"vod_id": "fixture", "vod_pic": self.player["url"]},
                             {"vod_id": "direct", "vod_pic": "https://image.example.com/a.jpg"}]}
        worker = {"handle": lambda *args: json.dumps(original), "_quiet_stdout": contextlib.nullcontext}
        self.payload.install_session_proxy(worker)
        for method in ("home", "category", "detail", "search"):
            result = worker["handle"](method, {})
            self.assertEqual("http://managed.invalid/local?type=video&id=a%2Bb", result["list"][0]["vod_pic"])
            self.assertEqual(original["list"][1], result["list"][1])
            self.assertEqual("fixture", result["list"][0]["vod_id"])

    def test_full_image_callback_keeps_same_local_handler_and_bounded_exact_bytes(self):
        self.local = [200, "image/jpeg", b"\xff\xd8\xff" + b"x" * 160000 + b"\xff\xd9"]
        result = self.worker["handle"]("proxy", {"target": "/local?type=image&id=a%2Bb",
                                                "range": "", "head": False})
        self.assertEqual(("local", {"type": "image", "id": "a+b", "headers": {}}), self.calls[-1])
        self.assertEqual(self.local[2], base64.b64decode(result["body"], validate=True))
        self.assertEqual(hashlib.sha256(self.local[2]).hexdigest(), result["sha256"])
        self.local[2] = bytes(self.payload.MAX_IMAGE_BYTES + 1)
        with self.assertRaises(ValueError):
            self.worker["handle"]("proxy", {"target": "/local?type=image", "range": "", "head": False})

    def test_base_proxy_factory_is_available_after_load_before_source_compilation(self):
        class Base:
            def getProxyUrl(self, local=True):
                raise NotImplementedError("Proxy runtime is not available in local tests")
        class Source(Base):
            pass
        class Wrapper:
            def _load_inner_spider_class(self, source_text):
                self.observed_url = Base().getProxyUrl()
                return Source
        wrapper = Wrapper()
        stream = SimpleNamespace(readline=lambda _: b'{"kind":"loader_ack"}\n')
        worker = {"_load_atvp": lambda: None, "_spider": wrapper,
                  "_base_spider": SimpleNamespace(Spider=Base)}
        emitted = []
        self.payload.emit = emitted.append
        self.payload.install_loader_observer(worker, stream)
        worker["_load_atvp"]()
        self.assertIs(Source, wrapper._load_inner_spider_class("trusted source bytes"))
        self.assertEqual("http://127.0.0.1:9978/proxy?do=py", wrapper.observed_url)
        self.assertEqual("loader_source", emitted[0]["kind"])
        self.assertEqual([], self.calls)
        for unsupported in (False, "true", 1, None):
            with self.subTest(local=unsupported), self.assertRaises(ValueError):
                Base().getProxyUrl(unsupported)

    def test_source_defined_proxy_factory_is_preserved(self):
        class Base:
            pass
        class Source:
            def getProxyUrl(self, local=True):
                return "source-owned-factory"
        original = Source.getProxyUrl
        wrapper_type = type("Wrapper", (), {"_load_inner_spider_class": lambda *_: Source})
        worker = {"_load_atvp": lambda: None, "_spider": wrapper_type(),
                  "_base_spider": SimpleNamespace(Spider=Base)}
        self.payload.emit = lambda _: None
        self.payload.install_loader_observer(
            worker, SimpleNamespace(readline=lambda _: b'{"kind":"loader_ack"}\n'))
        worker["_load_atvp"]()
        self.assertIs(Source, worker["_spider"]._load_inner_spider_class("trusted fixture"))
        self.assertIs(original, Source.getProxyUrl)

    def test_base_factory_uses_the_existing_play_rewrite_and_local_callback(self):
        self.player["url"] = self.payload.managed_proxy_url(object()) + "&type=video&id=a%2Bb"
        result = self.worker["handle"]("play", {"proxy_base": "http://managed.invalid"})
        self.assertEqual("http://managed.invalid/local?type=video&id=a%2Bb", result["url"])
        self.assertEqual(self.body, base64.b64decode(self.proxy()["body"], validate=True))

    def test_fixed_worker_inherited_and_plain_spider_init_play_local_and_destroy(self):
        module = Path(__file__).resolve().parents[1]
        program = r'''
import base64,contextlib,hashlib,importlib.util,io,json,pathlib,runpy,sys
module=pathlib.Path(sys.argv[1]);kind=sys.argv[2];runtime=module/"server/src/main/resources/worker/runtime"
sys.path.insert(0,str(runtime))
sys.argv=[str(runtime/"t4worker.py"),str(runtime/"Atvp.py")]
worker=runpy.run_path(sys.argv[0],run_name="trusted_worker_proxy_fixture")["main"].__globals__
spec=importlib.util.spec_from_file_location("trusted_proxy_payload",module/"managed-source/egress_payload.py")
adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
events=[];adapter.emit=events.append
adapter.install_loader_observer(worker,io.BytesIO(b'{"kind":"loader_ack"}\n'))
adapter.install_session_proxy(worker)
worker["_load_atvp"]()
source=("from base.spider import Spider as Base\nclass Spider(Base):\n"
        if kind=="inherited" else "class Spider:\n")+"""
 def init(self,extend=""):
  self.proxy=self.getProxyUrl()
  self.counter=0
 def getName(self):return "trusted-proxy-fixture"
 def playerContent(self,flag,ident,vip):
  self.counter+=1
  return {"parse":0,"url":self.proxy+"&type=video&id=a%2Bb"}
 def localProxy(self,params):
  assert params["type"]=="video" and params["id"]=="a+b"
  assert params["headers"]=={"Range":"bytes=0-32767"}
  assert self.counter==2
  return [206,"video/mp2t",bytes([71])+bytes(187),{"Content-Range":"bytes 0-187/188"}]
 def destroy(self):self.counter=-1
"""
wrapper=worker["_spider"]
inner=wrapper._load_inner_spider_class(source)()
with contextlib.redirect_stdout(io.StringIO()):
 inner.init()
wrapper._inner=inner
for _ in range(2):
 value=worker["handle"]("play",{"flag":"fixture","play":"fixture","proxy_base":"http://managed.invalid"})
 assert value["url"]=="http://managed.invalid/local?type=video&id=a%2Bb"
for head in (False,True):
 value=worker["handle"]("proxy",{"target":"/local?type=video&id=a%2Bb",
  "range":"bytes=0-32767","head":head})
 body=base64.b64decode(value["body"],validate=True)
 assert value["bytes"]==188 and body==(b"" if head else bytes([71])+bytes(187))
 assert value["sha256"]==hashlib.sha256(body).hexdigest()
assert worker["handle"]("destroy",{})=={"status":"closed"} and inner.counter==-1
assert events==[{"kind":"loader_source","sourceTextSha256":hashlib.sha256(source.encode()).hexdigest()}]
print(json.dumps({"fixedWorker":True,"playCalls":2,"proxyCalls":2,"destroyed":True,
 "sourceFixtureOnly":True,"networkRequested":False}))
'''
        environment = {key: value for key, value in os.environ.items()
                       if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
        for kind in ("inherited", "plain"):
            with self.subTest(kind=kind):
                child = subprocess.run([sys.executable, "-I", "-c", program, str(module), kind],
                                       capture_output=True, timeout=15, env=environment)
                self.assertEqual(0, child.returncode, child.stderr.decode("utf-8", errors="replace"))
                self.assertEqual({"fixedWorker": True, "playCalls": 2, "proxyCalls": 2,
                                  "destroyed": True, "sourceFixtureOnly": True, "networkRequested": False},
                                 json.loads(child.stdout))

    def test_external_fragment_duplicate_or_oversize_targets_never_call_local(self):
        for target in ("https://example.invalid/local?x=1", "/else?x=1", "/local?x=1#fragment",
                       "/local?a=1&a=2", "/local?" + "&".join("p%d=v" % n for n in range(25))):
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.proxy(target=target)
        self.assertEqual([], self.calls)

    def test_oversize_mime_header_and_invalid_body_fail_without_partial_result(self):
        for local in ([200, "video/mp2t", bytes(2097153)], [200, "image/png", bytes(32769)],
                      [200, "video/mp2t\r\nInjected: yes", b"x"],
                      [200, "video/mp2t", object()], [True, "video/mp2t", b"x"],
                      [200, "video/mp2t", b"x", []], [302, "text/plain", b"x"]):
            self.local = local
            with self.subTest(status=local[0]), self.assertRaises(ValueError):
                self.proxy()


if __name__ == "__main__":
    unittest.main()
