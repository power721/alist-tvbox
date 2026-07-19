# Raw Python backend_parse Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run raw `.py` subscription plugins through `Atvp.py` so `self.backend_parse = True` gets the same directory, detail, backend parse, and backend play behavior as encrypted `.txt` plugins.

**Architecture:** Keep the backend cache and authenticated `.py` endpoint unchanged. Change raw Python ext to load `Atvp.py` as the PyProxy delegate, provide the raw `.py` file as `source`, and mark it with `raw: true`; Atvp skips only secspider decryption for that payload and then reuses its existing backend-parse runtime. Add Java protocol tests and standard-library Python behavior tests.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, Mockito, AssertJ, Python 3 `unittest`, `unittest.mock`, requests, PyCryptodome, lxml.

---

## File Map

- Modify `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`: assert raw Python ext uses Atvp loader, `.py` source, and `raw: true` in both run modes.
- Modify `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`: build raw Python payload with Atvp loader and raw source marker.
- Modify `src/test/python/test_Atvp_raw_backend_parse.py`: create raw ext fixtures, prove decryption is skipped, and exercise `backend_parse=True` category/detail/parse/play behavior.
- Modify `src/main/resources/static/Atvp.py`: add an explicit raw payload predicate and bypass only the encrypted package decryptor for raw sources.

### Task 1: Lock the raw Python subscription protocol

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: Write the failing Java protocol assertions**

In `rawPythonPluginShouldUsePyProxyLoaderAndLocalProxyInEveryRunMode`, replace the current raw payload assertions with:

```java
assertThat(payload)
        .containsEntry("loader", "http://atv/Atvp.py")
        .containsEntry("source", "http://atv/plugins/web/7.py")
        .containsEntry("raw", true)
        .containsEntry("api", "http://atv")
        .containsEntry("token", "vod-token")
        .containsEntry("secret", "secret")
        .containsEntry("data", "{\"site\":\"demo\"}")
        .containsEntry("local_proxy_config", localProxyConfig);
```

Also assert the raw payload has no direct loader to the plugin file:

```java
assertThat(payload.get("loader")).isNotEqualTo("http://atv/plugins/web/7.py");
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -q -Dtest=SubscriptionServiceTest#rawPythonPluginShouldUsePyProxyLoaderAndLocalProxyInEveryRunMode test
```

Expected: failure because the current helper emits `loader=/plugins/web/7.py` and no `source/raw` fields.

- [ ] **Step 3: Implement the Atvp-wrapped raw payload**

In `SubscriptionService.buildPluginExtPayload`, keep the existing `.txt` branch and add the raw branch:

```java
if (rawPython) {
    map.put("loader", baseUrl + "/Atvp.py");
    map.put("source", baseUrl + "/plugins/" + contentToken + "/" + plugin.getId() + ".py");
    map.put("raw", true);
} else {
    map.put("source", baseUrl + "/plugins/" + contentToken + "/" + plugin.getId() + ".txt");
}
```

Do not change `selectPluginApi`; raw plugins continue using `csp_PyProxy`. Preserve the existing token, secret, data, and local proxy assignments.

- [ ] **Step 4: Run focused Java tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=SubscriptionServiceTest test
```

Expected: all subscription tests pass, including `.txt` Java/native mode regression assertions.

- [ ] **Step 5: Commit the ext protocol change**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "feat: wrap raw Python plugins with Atvp"
```

### Task 2: Add failing Atvp raw-source behavior tests

**Files:**
- Create: `src/test/python/test_Atvp_raw_backend_parse.py`

- [ ] **Step 1: Create a standard-library unittest fixture**

Create a test module which loads the repository Atvp implementation without pytest:

```python
import base64
import json
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
MODULE = SourceFileLoader("atvp_raw_backend_parse", str(ROOT / "main/resources/static/Atvp.py")).load_module()
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
            "local_proxy_config": {},
        }
        return base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()

    def init_inner(self, source):
        with (
            patch.object(Spider, "_load_source", return_value=source),
            patch.object(Spider, "_decrypt_secspider_source", side_effect=AssertionError("raw source must not decrypt")),
        ):
            self.spider.init(self.build_ext())

    def test_raw_source_skips_secspider_decryption(self):
        self.init_inner("class Spider:\n    def init(self, extend=\"\"):\n        return None\n")
        self.assertIsNotNone(self.spider._inner)

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

        self.spider.post = Mock(return_value=Response(text=json.dumps({"list": [{"vod_id": "share", "vod_name": "Parsed"}]})))
        parsed = self.spider.detailContent(["https://pan.example/share"])
        self.assertEqual(parsed["list"][0]["vod_name"], "Parsed")
        self.spider.fetch = Mock(return_value=Response(text=json.dumps({"parse": 0, "url": "https://video.example/demo.m3u8"})))
        played = self.spider.playerContent("网盘", "1@share", [])
        self.assertEqual(played["url"], "https://video.example/demo.m3u8")
        self.spider.post.assert_called_once()
        self.spider.fetch.assert_called_once()


if __name__ == "__main__":
    unittest.main()
```

The fixture deliberately makes `_decrypt_secspider_source` fail if invoked and asserts the existing `backend_parse` transformations, so the test fails before the raw branch exists.

- [ ] **Step 2: Run the Python tests and verify RED**

Run:

```bash
python -m unittest discover -s src/test/python -p 'test_Atvp_raw_backend_parse.py' -v
```

Expected: the module fails during `init` because current Atvp always calls `_decrypt_secspider_source`.

### Task 3: Implement raw source handling in Atvp.py

**Files:**
- Modify: `src/main/resources/static/Atvp.py`
- Modify: `src/test/python/test_Atvp_raw_backend_parse.py`

- [ ] **Step 1: Add an explicit raw payload predicate**

Add this method near `_decode_ext_payload`:

```python
def _is_raw_source(self, payload):
    return isinstance(payload, dict) and payload.get("raw") is True
```

- [ ] **Step 2: Select raw text before the decryptor**

Change `init` from:

```python
package_text = self._load_source(source)
source_text = self._decrypt_secspider_source(package_text)
```

to:

```python
package_text = self._load_source(source)
source_text = package_text if self._is_raw_source(payload) else self._decrypt_secspider_source(package_text)
```

Do not alter `_split_ext`, `_compose_inner_extend`, `_resolve_backend_api`, or any `backend_parse` method.

- [ ] **Step 3: Run Python tests and verify GREEN**

Run:

```bash
python -m unittest discover -s src/test/python -p 'test_Atvp_raw_backend_parse.py' -v
```

Expected: both tests pass, including backend `/parse` and `/play` mock calls.

- [ ] **Step 4: Run the existing Atvp Python test suite when available**

The source repository for Atvp tests is `/home/harold/workspace/atv-spiders`. Run:

```bash
python -m unittest discover -s /home/harold/workspace/atv-spiders/py/tests -p 'test_Atvp.py' -v
```

Expected: all existing Atvp tests pass; this confirms `.txt` encrypted behavior is unchanged.

- [ ] **Step 5: Commit the raw Atvp runtime**

```bash
git add src/main/resources/static/Atvp.py src/test/python/test_Atvp_raw_backend_parse.py
git commit -m "feat: support backend parse for raw Python sources"
```

### Task 4: Complete verification and review

**Files:**
- Verify all files changed in Tasks 1-3.

- [ ] **Step 1: Run focused Java and Python tests**

```bash
mvn -q -Dtest=SubscriptionServiceTest,PluginServiceTest,PluginContentControllerTest test
python -m unittest discover -s src/test/python -p 'test_Atvp_raw_backend_parse.py' -v
```

Expected: all commands exit `0`.

- [ ] **Step 2: Run full backend and frontend verification**

```bash
mvn test -q
```

From `web-ui/`:

```bash
npm test
npm run build
```

Expected: all commands exit `0`.

- [ ] **Step 3: Review protocol and diff scope**

```bash
git diff --check master...HEAD
git status --short --branch
git diff --stat master...HEAD
```

Expected: no whitespace errors, no generated files, and changes limited to the Atvp runtime, subscription payload helper/tests, Python behavior test, and plan/spec documents.

- [ ] **Step 4: Confirm protocol manually**

The final assertions must establish:

```text
raw .py -> api=csp_PyProxy
raw .py ext.loader=/Atvp.py
raw .py ext.source=/plugins/{token}/{id}.py
raw .py ext.raw=true
.txt -> existing source/decrypt flow with no raw marker
```
