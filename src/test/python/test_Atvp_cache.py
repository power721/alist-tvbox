import json
import os
import tempfile
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE = SourceFileLoader(
    "atvp_cache",
    str(ROOT / "src/main/resources/static/Atvp.py"),
).load_module()


class _CacheSpider(MODULE._FallbackSpider):
    """_FallbackSpider 是 ABC(abstractmethod init),补一个最小 init 才能实例化。"""

    def init(self, extend=""):
        pass


class TestAtvpCache(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cache_file = str(Path(self.tmp.name) / "site.json")
        self._reset()
        os.environ["ATVP_CACHE_FILE"] = self.cache_file

    def tearDown(self):
        os.environ.pop("ATVP_CACHE_FILE", None)
        self.tmp.cleanup()

    @staticmethod
    def _reset():
        # 模拟 worker 进程重启:丢弃进程级缓存单例与 spider 单例
        MODULE._kv_store = None
        MODULE._FallbackSpider._instance = None
        _CacheSpider._instance = None

    def _new_spider(self):
        return _CacheSpider()

    def test_set_get_roundtrip(self):
        spider = self._new_spider()
        spider.setCache("token", "abc123")

        self.assertEqual(spider.getCache("token"), "abc123")
        self.assertTrue(Path(self.cache_file).is_file())
        self.assertEqual(json.loads(Path(self.cache_file).read_text()), {"token": "abc123"})

    def test_persistence_across_reload(self):
        self._new_spider().setCache("cookie", "sid=xyz")

        self._reset()
        spider = self._new_spider()
        self.assertEqual(spider.getCache("cookie"), "sid=xyz")

    def test_non_string_values_coerced(self):
        spider = self._new_spider()
        spider.setCache("count", 42)
        spider.setCache(7, "by-int-key")

        self.assertEqual(spider.getCache("count"), "42")
        self.assertEqual(spider.getCache(7), "by-int-key")
        self.assertEqual(spider.getCache("7"), "by-int-key")

    def test_del_cache(self):
        spider = self._new_spider()
        spider.setCache("k", "v")
        spider.delCache("k")

        self.assertIsNone(spider.getCache("k"))
        self.assertEqual(json.loads(Path(self.cache_file).read_text()), {})

    def test_del_missing_key_is_silent(self):
        spider = self._new_spider()
        spider.delCache("never-exists")

        self.assertIsNone(spider.getCache("never-exists"))

    def test_missing_key_returns_none(self):
        self.assertIsNone(self._new_spider().getCache("absent"))

    def test_memory_fallback_without_env(self):
        os.environ.pop("ATVP_CACHE_FILE", None)
        self._reset()
        spider = self._new_spider()
        spider.setCache("k", "v")

        self.assertEqual(spider.getCache("k"), "v")
        self.assertFalse(Path(self.cache_file).exists())

    def test_corrupt_file_starts_empty(self):
        Path(self.cache_file).write_text("{not-json", encoding="utf-8")

        spider = self._new_spider()
        self.assertIsNone(spider.getCache("token"))
        spider.setCache("token", "fresh")
        self.assertEqual(json.loads(Path(self.cache_file).read_text()), {"token": "fresh"})

    def test_write_failure_does_not_raise(self):
        # .tmp 路径被目录占用 → 原子写必然失败;setCache 只 log 不炸插件链路
        Path(self.cache_file + ".tmp").mkdir()
        spider = self._new_spider()
        spider.setCache("k", "v")

        self.assertEqual(spider.getCache("k"), "v")
        self.assertFalse(Path(self.cache_file).exists())


if __name__ == "__main__":
    unittest.main()
