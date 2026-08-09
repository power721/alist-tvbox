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
        self.assertTrue(parsed["_atvp_backend_parse"])

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
            params={"id": "1@share", "type": "client-proxy"},
            timeout=10,
        )

    def test_backend_parse_sends_cached_plugin_title(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
'''
        self.init_inner(source)
        share_url = "https://pan.quark.cn/s/demo"
        self.spider._cache_detail_result({
            "list": [{
                "vod_name": "测试剧名",
                "vod_play_url": f"夸克${share_url}",
            }],
        })
        self.spider.post = Mock(
            return_value=Response(
                text=json.dumps({"list": [{"vod_name": "quark@demo@"}]})
            )
        )

        parsed = self.spider.detailContent([share_url])

        self.assertEqual(parsed["list"][0]["vod_name"], "测试剧名")
        self.spider.post.assert_called_once_with(
            "https://atv.example/parse/demo",
            json={"url": share_url, "title": "测试剧名"},
            params={"ac": "play"},
            timeout=10,
        )

    def test_group_media_link_caches_plugin_title_for_backend_parse(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
'''
        self.init_inner(source)
        share_url = "https://pan.quark.cn/s/group-demo"

        self.spider._cache_detail_result({
            "list": [{
                "vod_name": "分组剧名",
                "group": [{
                    "name": "夸克",
                    "media": [{"name": "资源一", "url": share_url}],
                }],
            }],
        })

        self.assertEqual(
            self.spider._detail_result_cache[share_url]["vod_name"],
            "分组剧名",
        )

    def test_backend_parse_sends_inherited_search_keyword(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
'''
        self.init_inner(source)
        share_url = "https://pan.quark.cn/s/search-demo"
        self.spider._remember_result_keywords({
            "list": [{"vod_id": "movie-1", "vod_name": "源标题"}],
        }, "搜索关键词")
        self.spider._cache_detail_result({
            "list": [{
                "vod_name": "源标题",
                "vod_play_url": f"夸克${share_url}",
            }],
        }, self.spider._search_keyword_cache["movie-1"])
        self.spider.post = Mock(
            return_value=Response(text=json.dumps({"list": [{"vod_name": "quark@demo@"}]}))
        )

        self.spider.detailContent([share_url])

        self.spider.post.assert_called_once_with(
            "https://atv.example/parse/demo",
            json={"url": share_url, "title": "源标题", "keyword": "搜索关键词"},
            params={"ac": "play"},
            timeout=10,
        )

    def test_resume_id_reopens_original_vod_and_selected_drive(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
    def detailContent(self, ids):
        return {"list": [{
            "vod_id": ids[0],
            "vod_name": "凡人修仙传",
            "vod_play_from": "百度$$$夸克$$$UC",
            "vod_play_url": "百度$https://pan.baidu.com/s/demo$$$夸克$https://pan.quark.cn/s/demo$$$UC$https://drive.uc.cn/s/demo"
        }]}
'''
        self.init_inner(source)
        routes = "$$$".join(f"{index:02d}线路" for index in range(1, 11))
        urls = "$$$".join(
            f"S01E{index}$1@{185400 + index}@{index - 1}@0"
            for index in range(1, 11)
        )
        self.spider.post = Mock(return_value=Response(text=json.dumps({"list": [{
            "vod_id": "1$185562$1",
            "vod_name": "凡人修仙传",
            "vod_play_from": routes,
            "vod_play_url": urls,
        }]})))

        resources = self.spider.categoryContent("atvp_detail:173", "1", True, {})
        self.assertEqual([item["vod_name"] for item in resources["list"]], ["百度", "夸克", "UC"])

        parsed = self.spider.detailContent([resources["list"][0]["vod_id"]])
        resume_id = parsed["list"][0]["vod_id"]
        self.assertTrue(resume_id.startswith(self.spider.RESUME_PREFIX))
        self.assertEqual(self.spider._decode_resume_id(resume_id), {"id": "173", "playlist": 0})

        restored = self.spider.detailContent([resume_id])
        self.assertEqual(restored["list"][0]["vod_id"], resume_id)
        self.assertEqual(restored["list"][0]["vod_play_from"].split("$$$"), [
            f"{index:02d}线路" for index in range(1, 11)
        ])
        parse_calls = [
            call for call in self.spider.post.call_args_list
            if call.args[0] == "https://atv.example/parse/demo"
        ]
        self.assertEqual(len(parse_calls), 2)
        for call in parse_calls:
            self.assertEqual(call.kwargs["json"]["url"], "https://pan.baidu.com/s/demo")

    def test_resume_id_with_direct_share_calls_backend_parse(self):
        source = '''
class Spider:
    def init(self, extend=""):
        self.backend_parse = True
    def detailContent(self, ids):
        raise AssertionError("direct share resume must not call inner detail")
'''
        self.init_inner(source)
        share_url = "https://123pan.cn/s/cHCOTd-kdmM?pwd=0775"
        self.spider.post = Mock(return_value=Response(text=json.dumps({"list": [{
            "vod_id": "1$185600$1",
            "vod_name": "测试资源",
            "vod_play_from": "线路 1",
            "vod_play_url": "第1集$1@185600@0@0",
        }]})))
        resume_id = self.spider._encode_resume_id(share_url, 0)

        restored = self.spider.detailContent([resume_id])

        self.assertEqual(restored["list"][0]["vod_id"], resume_id)
        self.assertEqual(restored["list"][0]["vod_play_url"], "第1集$1@185600@0@0")
        self.spider.post.assert_called_once_with(
            "https://atv.example/parse/demo",
            json={"url": share_url},
            params={"ac": "play"},
            timeout=10,
        )


if __name__ == "__main__":
    unittest.main()
