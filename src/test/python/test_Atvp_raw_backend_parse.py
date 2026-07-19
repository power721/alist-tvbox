import base64
import json
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[3]
MODULE = SourceFileLoader(
    "atvp_raw_backend_parse",
    str(ROOT / "src/main/resources/static/Atvp.py"),
).load_module()
Spider = MODULE.Spider


class Response:
    def __init__(self, status_code=200, text=""):
        self.status_code = status_code
        self.text = text


class TestAtvpRawBackendParse(unittest.TestCase):
    def setUp(self):
        Spider._instance = None
        self.spider = Spider()

    def build_ext(self):
        payload = {
            "loader": "https://atv.example/Atvp.py",
            "api": "https://atv.example",
            "source": "https://atv.example/plugins/demo/7.py",
            "raw": True,
            "token": "demo",
            "data": {"site": "demo"},
            "local_proxy_config": {"ALI": {"enabled": True}},
        }
        return base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()

    def init_inner(self, source):
        with (
            patch.object(Spider, "_load_source", return_value=source),
            patch.object(
                Spider,
                "_decrypt_secspider_source",
                side_effect=AssertionError("raw source must not decrypt"),
            ),
        ):
            self.spider.init(self.build_ext())

    def test_raw_source_skips_secspider_decryption(self):
        self.init_inner(
            'class Spider:\n    def init(self, extend=""):\n        self.received_extend = extend\n'
        )

        self.assertIsNotNone(self.spider._inner)
        self.assertEqual(
            json.loads(self.spider._inner.received_extend),
            {
                "site": "demo",
                "token": "demo",
                "local_proxy_config": "{'ALI': {'enabled': True}}",
            },
        )

    def test_source_without_raw_marker_still_uses_secspider_decryption(self):
        payload = {
            "api": "https://atv.example",
            "source": "https://atv.example/plugins/demo/7.txt",
            "token": "demo",
        }
        extend = base64.b64encode(
            json.dumps(payload, separators=(",", ":")).encode()
        ).decode()
        source = 'class Spider:\n    def init(self, extend=""):\n        return None\n'

        with (
            patch.object(Spider, "_load_source", return_value="encrypted") as load_source,
            patch.object(Spider, "_decrypt_secspider_source", return_value=source) as decrypt,
        ):
            self.spider.init(extend)

        load_source.assert_called_once_with(payload["source"])
        decrypt.assert_called_once_with("encrypted")

    def test_backend_parse_rewrites_category_and_uses_backend_parse_and_play(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
    def categoryContent(self, tid, pg, filter, extend):
        return {"list": [{"vod_id": "movie-1", "vod_name": "Demo"}], "page": 1}
    def detailContent(self, ids):
        return {"list": [{"vod_id": ids[0], "vod_name": "Demo", "vod_play_from": "网盘", "vod_play_url": "网盘$1@share"}]}
'''
        self.init_inner(source)

        home = self.spider.categoryContent("home", "1", False, {})
        self.assertTrue(home["list"][0]["vod_id"].startswith(self.spider.DETAIL_PREFIX))
        detail = self.spider.categoryContent(home["list"][0]["vod_id"], "1", False, {})
        self.assertEqual(detail["list"][0]["vod_id"], "1@share")

        self.spider.post = Mock(
            return_value=Response(
                text=json.dumps({"list": [{"vod_id": "share", "vod_name": "Parsed"}]})
            )
        )
        parsed = self.spider.detailContent(["https://pan.example/share"])
        self.assertEqual(parsed["list"][0]["vod_name"], "Parsed")

        self.spider.fetch = Mock(
            return_value=Response(
                text=json.dumps(
                    {"parse": 0, "url": "https://video.example/demo.m3u8"}
                )
            )
        )
        played = self.spider.playerContent("网盘", "1@share", [])
        self.assertEqual(played["url"], "https://video.example/demo.m3u8")
        self.spider.post.assert_called_once_with(
            "https://atv.example/parse/demo",
            json={"url": "https://pan.example/share"},
            params={"ac": "play"},
            timeout=10,
        )
        self.spider.fetch.assert_called_once_with(
            "https://atv.example/play/demo",
            params={"id": "1@share"},
            timeout=10,
        )


if __name__ == "__main__":
    unittest.main()
