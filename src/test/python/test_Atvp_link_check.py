import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path
from unittest.mock import Mock


ROOT = Path(__file__).resolve().parents[3]
MODULE = SourceFileLoader(
    "atvp_link_check",
    str(ROOT / "src/main/resources/static/Atvp.py"),
).load_module()
Spider = MODULE.Spider


class Response:
    def __init__(self, payload=None, status_code=200):
        self.status_code = status_code
        self._payload = payload or {}
        self.text = ""

    def json(self):
        return self._payload


class InnerSpider:
    def __init__(self, result):
        self.result = result

    def detailContent(self, ids):
        return self.result

    def searchContent(self, key, quick, pg):
        return self.result


class TestAtvpLinkCheck(unittest.TestCase):
    def setUp(self):
        Spider._instance = None
        self.spider = Spider()
        self.spider._backend_api = "https://atv.example"
        self.spider._vod_token = "demo"
        self.spider._filters = []

    def test_detail_filters_bad_play_and_group_links(self):
        good = "https://pan.quark.cn/s/good"
        bad = "https://www.alipan.com/s/bad"
        locked = "https://pan.baidu.com/s/locked"
        result = {
            "list": [{
                "vod_name": "Demo",
                "vod_play_from": "夸克$$$阿里$$$直链",
                "vod_play_url": f"夸克${good}$$$阿里$push://{bad}#百度${locked}$$$播放$https://video.example/a.m3u8",
                "group": [{
                    "name": "网盘",
                    "media": [
                        {"name": "good", "url": good},
                        {"name": "bad", "url": bad},
                        {"name": "direct", "url": "https://video.example/b.mp4"},
                    ],
                }],
            }],
        }
        self.spider._inner = InnerSpider(result)
        self.spider.post = Mock(return_value=Response({
            "results": [
                {"url": good, "state": "ok"},
                {"url": bad, "state": "bad"},
                {"url": locked, "state": "locked"},
            ],
        }))

        filtered = self.spider.detailContent(["movie-1"])

        vod = filtered["list"][0]
        self.assertEqual(vod["vod_play_from"], "夸克$$$阿里$$$直链")
        self.assertEqual(
            vod["vod_play_url"],
            f"夸克${good}$$$百度${locked}$$$播放$https://video.example/a.m3u8",
        )
        self.assertEqual(
            [media["name"] for media in vod["group"][0]["media"]],
            ["good", "direct"],
        )
        self.spider.post.assert_called_once_with(
            "https://atv.example/check-links/demo",
            json={"items": [{"url": good}, {"url": bad}, {"url": locked}]},
            timeout=15,
        )

    def test_detail_keeps_links_when_check_fails(self):
        url = "https://pan.quark.cn/s/share"
        result = {"list": [{"vod_play_from": "夸克", "vod_play_url": f"夸克${url}"}]}
        self.spider._inner = InnerSpider(result)
        self.spider.post = Mock(side_effect=TimeoutError("timeout"))

        self.assertEqual(self.spider.detailContent(["movie-1"]), result)

    def test_detail_checks_many_links_in_one_request(self):
        urls = [f"https://pan.quark.cn/s/{index}" for index in range(51)]
        result = {"list": [{
            "vod_play_from": "夸克",
            "vod_play_url": "#".join(f"资源{index}${url}" for index, url in enumerate(urls)),
        }]}
        self.spider._inner = InnerSpider(result)
        self.spider.post = Mock(return_value=Response())

        self.spider.detailContent(["movie-1"])

        self.spider.post.assert_called_once()
        self.assertEqual(len(self.spider.post.call_args.kwargs["json"]["items"]), 51)

    def test_search_filters_bad_share_items_before_category_normalization(self):
        good = "https://pan.quark.cn/s/good-search"
        bad = "https://www.alipan.com/s/bad-search"
        result = {"list": [
            {"vod_id": good, "vod_name": "Good"},
            {"vod_id": bad, "vod_name": "Bad"},
            {"vod_id": "https://video.example/live.m3u8", "vod_name": "Video"},
        ]}
        self.spider._inner = InnerSpider(result)
        self.spider._inner.backend_parse = True
        self.spider.post = Mock(return_value=Response({
            "results": [
                {"url": good, "state": "ok"},
                {"url": bad, "state": "bad"},
            ],
        }))

        filtered = self.spider.searchContent("demo", False)

        self.assertEqual([item["vod_name"] for item in filtered["list"]], ["Good", "Video"])
        self.assertEqual(filtered["list"][0]["vod_id"], self.spider.DETAIL_PREFIX + good)
        self.spider.post.assert_called_once_with(
            "https://atv.example/check-links/demo",
            json={"items": [{"url": good}, {"url": bad}]},
            timeout=15,
        )

    def test_category_detail_checks_group_links_before_building_folders(self):
        good = "https://pan.quark.cn/s/good-category"
        bad = "https://pan.quark.cn/s/bad-category"
        result = {"list": [{
            "vod_name": "昭阳公主",
            "group": [{
                "name": "夸克",
                "media": [
                    {"name": "有效资源", "url": good},
                    {"name": "失效资源", "url": bad},
                ],
            }],
        }]}
        self.spider._inner = InnerSpider(result)
        self.spider._inner.backend_parse = True
        self.spider.post = Mock(return_value=Response({
            "results": [
                {"url": good, "state": "ok"},
                {"url": bad, "state": "bad"},
            ],
        }))

        category = self.spider.categoryContent(
            self.spider.DETAIL_PREFIX + "509723", "1", True, {}
        )

        self.assertEqual(category["list"][0]["vod_name"], "夸克 (1)")
        self.spider.post.assert_called_once_with(
            "https://atv.example/check-links/demo",
            json={"items": [{"url": good}, {"url": bad}]},
            timeout=15,
        )


if __name__ == "__main__":
    unittest.main()
