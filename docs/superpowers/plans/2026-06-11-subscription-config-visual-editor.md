# 订阅配置可视化编辑器 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用图形界面替代订阅 `override` / 全局配置的原始 JSON 输入,支持站点(含内置/插件)黑白名单、上游站点改名/排序、自定义站点与解析、壁纸/flags/ads 等。

**Architecture:** 后端先把黑/白名单解析改成显式真值表算法(`resolveAndApplyFilters`,纯静态可测),再加只读目录端点(`getCatalog`/`buildCatalog`,纯静态可测)。前端把 config↔表单的转换逻辑放进纯模块 `subscriptionConfig.mjs`(node:test 单测),`SubscriptionConfigEditor.vue` 只做 Element Plus 视图 + 调用纯模块,供单订阅与全局配置共用。

**Tech Stack:** 后端 Spring Boot / JUnit5 / Mockito / AssertJ;前端 Vue3 + TS + Element Plus,纯逻辑用 `.mjs` + `node:test`。

参考规格:`docs/superpowers/specs/2026-06-11-subscription-config-visual-editor-design.md`

---

## Phase 1 — 后端:黑/白名单解析算法(独立正确性修复)

### Task 1: 用 `resolveAndApplyFilters` 替换 `applySitesFilter`

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`(新建)

- [ ] **Step 1: 写失败测试(真值表)**

新建 `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceTest {

    // ---- helpers ----
    private Map<String, Object> site(String key) {
        Map<String, Object> s = new HashMap<>();
        s.put("key", key);
        s.put("name", key);
        return s;
    }

    private Map<String, Object> parse(String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        return p;
    }

    private Map<String, Object> config(String... siteKeys) {
        List<Map<String, Object>> sites = new ArrayList<>();
        for (String k : siteKeys) sites.add(site(k));
        Map<String, Object> c = new HashMap<>();
        c.put("sites", sites);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<String> siteKeys(Map<String, Object> c) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) c.get("sites")) keys.add((String) s.get("key"));
        return keys;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseNames(Map<String, Object> c) {
        List<String> names = new ArrayList<>();
        Object obj = c.get("parses");
        if (obj instanceof List) for (Map<String, Object> p : (List<Map<String, Object>>) obj) names.add((String) p.get("name"));
        return names;
    }

    private Map<String, Object> whitelist(String... keys) {
        return new HashMap<>(Map.of("sites-whitelist", new ArrayList<>(List.of(keys))));
    }

    private Map<String, Object> blacklistSites(String... keys) {
        Map<String, Object> bl = new HashMap<>();
        bl.put("sites", new ArrayList<>(List.of(keys)));
        return new HashMap<>(Map.of("blacklist", bl));
    }

    // ---- truth table ----

    @Test
    void subscriptionWhitelistWinsAndIgnoresEverything() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("A"), whitelist("A"));
        assertThat(siteKeys(c)).containsExactly("A");
        assertThat(c).doesNotContainKeys("sites-whitelist", "sites-blacklist", "blacklist");
    }

    @Test
    void subscriptionBlacklistReplacesGlobal() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("A"), blacklistSites("B"));
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "C"); // global A ignored, sub removes B
    }

    @Test
    void globalWhitelistUsedWhenNoSubscriptionFilter() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, whitelist("A", "B"), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void globalBlacklistUsedWhenNoSubscriptionFilter() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("B"), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void subscriptionWhitelistBeatsSubscriptionBlacklist() {
        Map<String, Object> c = config("A", "B");
        Map<String, Object> override = whitelist("A");
        override.putAll(blacklistSites("A")); // both whitelist + blacklist present
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
    }

    @Test
    void emptyFiltersStillRemoveAlist1() {
        Map<String, Object> c = config("A", "Alist1", "B");
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void legacyTopLevelSitesBlacklistIsHonored() {
        Map<String, Object> c = config("A", "B");
        Map<String, Object> override = new HashMap<>();
        override.put("sites-blacklist", new ArrayList<>(List.of("B")));
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
    }

    @Test
    void blacklistParsesRemovedByName() {
        Map<String, Object> c = config("A");
        c.put("parses", new ArrayList<>(List.of(parse("虾米"), parse("Json"))));
        Map<String, Object> bl = new HashMap<>();
        bl.put("parses", new ArrayList<>(List.of("虾米")));
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), new HashMap<>(Map.of("blacklist", bl)));
        assertThat(parseNames(c)).containsExactly("Json");
    }

    @Test
    void whitelistModeIgnoresParseBlacklist() {
        Map<String, Object> c = config("A", "B");
        c.put("parses", new ArrayList<>(List.of(parse("虾米"))));
        Map<String, Object> override = whitelist("A");
        Map<String, Object> bl = new HashMap<>();
        bl.put("parses", new ArrayList<>(List.of("虾米")));
        override.put("blacklist", bl);
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
        assertThat(parseNames(c)).containsExactly("虾米"); // parse NOT removed in whitelist mode
    }
}
```

- [ ] **Step 2: 运行,确认编译失败**

Run: `mvn test -Dtest=SubscriptionServiceTest`
Expected: 编译失败,`cannot find symbol: method resolveAndApplyFilters`。

- [ ] **Step 3: 实现 — 改静态 + 新增解析方法**

在 `SubscriptionService.java` 改三个方法签名为 `static`(方法体不变):
- `private void handleWhitelist(Map<String, Object> config)` → `private static void handleWhitelist(Map<String, Object> config)`
- `private void removeBlacklist(Map<String, Object> config)` → `private static void removeBlacklist(Map<String, Object> config)`
- `private void removeBlacklist(Map<String, Object> config, Map<String, Object> blacklist, String type)` → `private static void removeBlacklist(Map<String, Object> config, Map<String, Object> blacklist, String type)`

在类中新增(放在 `handleWhitelist` 上方即可):

```java
    static List<String> normalizeWhitelist(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object obj = source.get("sites-whitelist");
        if (obj instanceof List<?> list && !list.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> normalizeBlacklist(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        Object blacklist = source.get("blacklist");
        if (blacklist instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof List<?> list && !list.isEmpty()) {
                    result.put(String.valueOf(e.getKey()), new ArrayList<>(list));
                }
            }
        }
        Object sitesBlacklist = source.get("sites-blacklist");
        if (sitesBlacklist instanceof List<?> list && !list.isEmpty()) {
            List<Object> sites = (List<Object>) result.computeIfAbsent("sites", k -> new ArrayList<>());
            for (Object o : list) {
                if (!sites.contains(o)) {
                    sites.add(o);
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    static void resolveAndApplyFilters(Map<String, Object> config, Map<String, Object> globalConfig, Map<String, Object> override) {
        List<String> globalWhitelist = normalizeWhitelist(globalConfig);
        Map<String, Object> globalBlacklist = normalizeBlacklist(globalConfig);
        List<String> subWhitelist = normalizeWhitelist(override);
        Map<String, Object> subBlacklist = normalizeBlacklist(override);

        // 清除合并残留,避免外泄/重复应用
        config.remove("sites-whitelist");
        config.remove("sites-blacklist");
        config.remove("blacklist");

        List<String> finalWhitelist;
        Map<String, Object> finalBlacklist;
        if (subWhitelist != null) {
            finalWhitelist = subWhitelist;
            finalBlacklist = null;
        } else if (subBlacklist != null) {
            finalWhitelist = null;
            finalBlacklist = subBlacklist;
        } else if (globalWhitelist != null) {
            finalWhitelist = globalWhitelist;
            finalBlacklist = null;
        } else {
            finalWhitelist = null;
            finalBlacklist = globalBlacklist;
        }

        if (finalWhitelist != null) {
            config.put("sites-whitelist", finalWhitelist);
            handleWhitelist(config); // 白名单模式忽略黑名单
        } else {
            if (finalBlacklist != null) {
                config.put("blacklist", finalBlacklist);
            }
            removeBlacklist(config); // 应用黑名单对象 + 始终移除 Alist1
        }
    }

    private Map<String, Object> parseOverride(String override) {
        if (StringUtils.isBlank(override)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(override, Map.class);
        } catch (Exception e) {
            log.warn("parse override for filters failed", e);
            return new HashMap<>();
        }
    }
```

- [ ] **Step 4: 替换调用点并删死代码**

在 `subscription(String token, String apiUrl, String override, String sort)` 中,把:

```java
        // should after overrideConfig
        applySitesFilter(config);
```

替换为:

```java
        // should after overrideConfig
        resolveAndApplyFilters(config, getGlobalConfig(), parseOverride(override));
```

删除三个死方法:`applySitesFilter(Map)`、`handleWhitelistFilter(Map, List)`、`handleBlacklistFilter(Map, List)`(整段删除)。

- [ ] **Step 5: 运行测试,确认通过**

Run: `mvn test -Dtest=SubscriptionServiceTest`
Expected: 9 个测试全部 PASS。

- [ ] **Step 6: 全量编译确保无残留引用**

Run: `mvn -q -o compile test-compile`
Expected: BUILD SUCCESS(无 `applySitesFilter` 等未定义引用)。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "fix: resolve global/subscription site filters via explicit truth table"
```

---

## Phase 2 — 后端:订阅目录端点

### Task 2: `buildCatalog` + `getCatalog` + controller

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`

- [ ] **Step 1: 写失败测试(纯函数 buildCatalog)**

在 `SubscriptionServiceTest.java` 追加(import 顶部加 `import cn.har01d.alist_tvbox.entity.Plugin;`):

```java
    @Test
    void buildCatalogTagsUpstreamBuiltinPluginAndMapsPushKey() {
        Map<String, Object> c = config("csp_Bili"); // upstream
        c.put("parses", new java.util.ArrayList<>(List.of(parse("虾米"))));

        Plugin plugin = new Plugin();
        plugin.setName("我的插件");

        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "🟢 AList", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Push", true, "csp_Push", "推送", null),
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-1", false, "我的插件", "我的插件", plugin)
        );

        Map<String, Object> catalog = SubscriptionService.buildCatalog(c, sources);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sites = (List<Map<String, Object>>) catalog.get("sites");
        // 三类 + origin
        assertThat(sites).anySatisfy(s -> {
            assertThat(s).containsEntry("key", "csp_Bili").containsEntry("origin", "upstream");
        });
        assertThat(sites).anySatisfy(s -> {
            assertThat(s).containsEntry("key", "csp_AList").containsEntry("origin", "builtin");
        });
        // csp_Push 映射为 push_agent
        assertThat(sites).anySatisfy(s -> {
            assertThat(s).containsEntry("key", "push_agent").containsEntry("origin", "builtin");
        });
        assertThat(sites).anySatisfy(s -> {
            assertThat(s).containsEntry("key", "我的插件").containsEntry("origin", "plugin");
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parses = (List<Map<String, Object>>) catalog.get("parses");
        assertThat(parses).extracting(p -> p.get("name")).containsExactly("虾米");
    }
```

- [ ] **Step 2: 运行,确认编译失败**

Run: `mvn test -Dtest=SubscriptionServiceTest#buildCatalogTagsUpstreamBuiltinPluginAndMapsPushKey`
Expected: 编译失败,`cannot find symbol: method buildCatalog`。

- [ ] **Step 3: 实现 `buildCatalog`(静态)与 `getCatalog`**

在 `SubscriptionService.java` 新增:

```java
    @SuppressWarnings("unchecked")
    static Map<String, Object> buildCatalog(Map<String, Object> config, List<SubscriptionSourceService.SubscriptionSourceRef> sources) {
        java.util.Set<String> builtinPluginKeys = new HashSet<>();
        List<Map<String, Object>> sourceSites = new ArrayList<>();
        for (SubscriptionSourceService.SubscriptionSourceRef source : sources) {
            String key;
            String origin;
            if (source.builtin()) {
                key = "csp_Push".equals(source.siteKey()) ? "push_agent" : source.siteKey();
                origin = "builtin";
            } else if (source.plugin() != null) {
                key = source.plugin().getName();
                origin = "plugin";
            } else {
                continue;
            }
            if (key == null) {
                continue;
            }
            builtinPluginKeys.add(key);
            Map<String, Object> item = new HashMap<>();
            item.put("key", key);
            item.put("name", source.name() == null ? key : source.name());
            item.put("origin", origin);
            sourceSites.add(item);
        }

        List<Map<String, Object>> upstream = new ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        Object sitesObj = config.get("sites");
        if (sitesObj instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> s) || s.get("key") == null) {
                    continue;
                }
                String key = String.valueOf(s.get("key"));
                if (builtinPluginKeys.contains(key) || !seen.add(key)) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("key", key);
                item.put("name", s.get("name") == null ? key : s.get("name"));
                item.put("origin", "upstream");
                upstream.add(item);
            }
        }

        List<Map<String, Object>> parses = new ArrayList<>();
        Object parsesObj = config.get("parses");
        if (parsesObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> p && p.get("name") != null) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", String.valueOf(p.get("name")));
                    parses.add(item);
                }
            }
        }

        List<Map<String, Object>> allSites = new ArrayList<>();
        allSites.addAll(sourceSites);
        allSites.addAll(upstream);
        Map<String, Object> result = new HashMap<>();
        result.put("sites", allSites);
        result.put("parses", parses);
        return result;
    }

    public Map<String, Object> getCatalog(String sid) {
        Subscription sub = subscriptionRepository.findBySid(sid).orElseThrow(NotFoundException::new);
        String apiUrl = sub.getUrl() == null ? "" : sub.getUrl();
        Map<String, Object> config = new HashMap<>();
        config.put("sites", new ArrayList<>());
        for (String url : apiUrl.split(",")) {
            if (StringUtils.isBlank(url)) {
                continue;
            }
            String u = url.trim();
            String prefix = "";
            String[] parts = u.split("@", 2);
            if (parts.length == 2) {
                prefix = parts[0].trim() + "@";
                u = parts[1].trim();
            }
            overrideConfig(config, fixUrl(u), prefix, getConfigData(u));
        }
        return buildCatalog(config, subscriptionSourceService.findEnabledSources());
    }
```

- [ ] **Step 4: 加 controller 端点**

在 `SubscriptionController.java` 新增(放在 `global-config` 方法附近):

```java
    @GetMapping("/{sid}/catalog")
    public Map<String, Object> getCatalog(@PathVariable String sid) {
        return subscriptionService.getCatalog(sid);
    }
```

- [ ] **Step 5: 运行测试通过**

Run: `mvn test -Dtest=SubscriptionServiceTest`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java
git commit -m "feat: add subscription catalog endpoint for visual editor"
```

---

## Phase 3 — 前端:纯转换逻辑模块

### Task 3: `subscriptionConfig.mjs` + 单测

**Files:**
- Create: `web-ui/src/utils/subscriptionConfig.mjs`
- Test: `web-ui/src/utils/subscriptionConfig.test.mjs`
- Modify: `web-ui/package.json`(加 `test` 脚本)

- [ ] **Step 1: 写失败测试**

新建 `web-ui/src/utils/subscriptionConfig.test.mjs`:

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseOverride,
  detectFilterMode,
  disabledSiteKeys,
  whitelistKeys,
  disabledParseNames,
  siteOverrideMap,
  serialize,
  stringify,
} from './subscriptionConfig.mjs'

test('parseOverride: empty -> {}', () => {
  assert.deepEqual(parseOverride(''), {})
  assert.deepEqual(parseOverride('   '), {})
})

test('parseOverride: invalid -> null', () => {
  assert.equal(parseOverride('{bad'), null)
})

test('detectFilterMode reads whitelist/blacklist/object', () => {
  assert.equal(detectFilterMode({ 'sites-whitelist': ['a'] }), 'whitelist')
  assert.equal(detectFilterMode({ 'sites-blacklist': ['a'] }), 'blacklist')
  assert.equal(detectFilterMode({ blacklist: { sites: ['a'] } }), 'blacklist')
  assert.equal(detectFilterMode({}), 'none')
})

test('disabledSiteKeys unions legacy + object', () => {
  assert.deepEqual(
    disabledSiteKeys({ 'sites-blacklist': ['a'], blacklist: { sites: ['b', 'a'] } }).sort(),
    ['a', 'b']
  )
})

test('disabledParseNames from blacklist.parses', () => {
  assert.deepEqual(disabledParseNames({ blacklist: { parses: ['虾米'] } }), ['虾米'])
})

test('siteOverrideMap reads name/order from config.sites', () => {
  const m = siteOverrideMap({ sites: [{ key: 'a', name: '改名', order: 100 }, { key: 'b' }] })
  assert.deepEqual(m.a, { name: '改名', order: 100 })
  assert.deepEqual(m.b, {})
})

test('serialize: blacklist mode writes blacklist.sites, migrates legacy, drops sites-blacklist', () => {
  const base = { 'sites-blacklist': ['x'], rules: [{ name: 'keepme' }] }
  const state = {
    filterMode: 'blacklist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'builtin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [{ name: '虾米', enabled: false, isCustom: false }],
    wall: '',
    spider: '',
    flags: [],
    ads: [],
  }
  const out = serialize(base, state)
  assert.equal(out['sites-blacklist'], undefined)
  assert.deepEqual(out.blacklist.sites, ['b'])
  assert.deepEqual(out.blacklist.parses, ['虾米'])
  assert.deepEqual(out.rules, [{ name: 'keepme' }]) // unknown key preserved
  assert.equal(out.sites, undefined) // no rename/order overrides
})

test('serialize: whitelist mode writes sites-whitelist of enabled keys', () => {
  const state = {
    filterMode: 'whitelist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'plugin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [],
    wall: '',
    spider: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.deepEqual(out['sites-whitelist'], ['a'])
  assert.equal(out.blacklist, undefined)
})

test('serialize: upstream rename + order emit sites partial; custom site full', () => {
  const state = {
    filterMode: 'none',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: '新名', originalName: 'A', order: 50, isCustom: false },
      { key: 'mine', origin: 'custom', enabled: true, isCustom: true, name: '自定义', type: 3, api: 'csp_X', searchable: 1, quickSearch: 1, filterable: 1 },
    ],
    parses: [],
    wall: 'http://w',
    spider: '',
    flags: ['x'],
    ads: [],
  }
  const out = serialize({}, state)
  assert.deepEqual(out.sites.find((s) => s.key === 'a'), { key: 'a', name: '新名', order: 50 })
  const custom = out.sites.find((s) => s.key === 'mine')
  assert.equal(custom.type, 3)
  assert.equal(custom.api, 'csp_X')
  assert.equal(out.wall, 'http://w')
  assert.deepEqual(out.flags, ['x'])
})

test('stringify: empty object -> empty string', () => {
  assert.equal(stringify({}), '')
  assert.equal(stringify({ wall: 'x' }), '{"wall":"x"}')
})
```

- [ ] **Step 2: 运行,确认失败**

Run: `cd web-ui && node --test src/utils/subscriptionConfig.test.mjs`
Expected: FAIL(`Cannot find module './subscriptionConfig.mjs'`)。

- [ ] **Step 3: 实现纯模块**

新建 `web-ui/src/utils/subscriptionConfig.mjs`:

```js
// 订阅 override / 全局配置 的纯转换逻辑(无 Vue 依赖,便于单测)

export function parseOverride(text) {
  if (!text || !text.trim()) return {}
  try {
    const v = JSON.parse(text)
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {}
  } catch {
    return null // null 表示非法 JSON
  }
}

export function stringify(config) {
  const keys = Object.keys(config || {})
  if (keys.length === 0) return ''
  return JSON.stringify(config)
}

export function detectFilterMode(config) {
  const wl = config['sites-whitelist']
  if (Array.isArray(wl) && wl.length) return 'whitelist'
  const bl = config.blacklist
  const legacy = config['sites-blacklist']
  if ((Array.isArray(legacy) && legacy.length) || (bl && Array.isArray(bl.sites) && bl.sites.length)) return 'blacklist'
  return 'none'
}

export function disabledSiteKeys(config) {
  const set = new Set()
  if (Array.isArray(config['sites-blacklist'])) config['sites-blacklist'].forEach((k) => set.add(k))
  if (config.blacklist && Array.isArray(config.blacklist.sites)) config.blacklist.sites.forEach((k) => set.add(k))
  return [...set]
}

export function whitelistKeys(config) {
  return Array.isArray(config['sites-whitelist']) ? [...config['sites-whitelist']] : []
}

export function disabledParseNames(config) {
  return config.blacklist && Array.isArray(config.blacklist.parses) ? [...config.blacklist.parses] : []
}

// 返回 { key: {name?, order?} } —— config.sites 里的局部 override
export function siteOverrideMap(config) {
  const map = {}
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      if (s && s.key != null) {
        const o = {}
        if (s.name != null) o.name = s.name
        if (s.order != null) o.order = s.order
        map[String(s.key)] = o
      }
    }
  }
  return map
}

// 返回 config.sites 里 key ∉ catalogKeys 的项(自定义站点),原样
export function customSites(config, catalogKeys) {
  const set = new Set(catalogKeys)
  if (!Array.isArray(config.sites)) return []
  return config.sites.filter((s) => s && s.key != null && !set.has(String(s.key)))
}

const CUSTOM_SITE_KEYS = [
  'key', 'name', 'type', 'api', 'ext', 'jar', 'searchable', 'quickSearch',
  'filterable', 'changeable', 'indexs', 'timeout', 'order', 'style',
  'categories', 'header', 'playUrl', 'click',
]

function buildCustomSite(row) {
  const s = {}
  for (const k of CUSTOM_SITE_KEYS) {
    const v = row[k]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    s[k] = v
  }
  return s
}

function buildCustomParse(row) {
  const p = { name: row.name }
  if (row.type != null && row.type !== '') p.type = Number(row.type)
  if (row.url) p.url = row.url
  const ext = {}
  if (Array.isArray(row.flag) && row.flag.length) ext.flag = row.flag
  if (row.header && Object.keys(row.header).length) ext.header = row.header
  if (Object.keys(ext).length) p.ext = ext
  return p
}

function setOrDelete(config, key, value) {
  if (value === undefined || value === null || value === '') delete config[key]
  else config[key] = value
}

function setArrOrDelete(config, key, value) {
  if (Array.isArray(value) && value.length) config[key] = value
  else delete config[key]
}

// 把编辑器状态写回 config(保留未建模键)
export function serialize(baseConfig, state) {
  const config = JSON.parse(JSON.stringify(baseConfig || {}))

  // sites: 上游局部 override + 自定义站点
  const sites = []
  for (const row of state.sites) {
    if (row.isCustom) {
      sites.push(buildCustomSite(row))
    } else if (row.origin === 'upstream') {
      const o = {}
      if (row.name && row.name !== row.originalName) o.name = row.name
      else if (row.hadNameOverride && row.name) o.name = row.name
      if (row.order !== '' && row.order !== null && row.order !== undefined) o.order = Number(row.order)
      if (Object.keys(o).length) {
        o.key = row.key
        sites.push(o)
      }
    }
  }
  setArrOrDelete(config, 'sites', sites)

  // 过滤键:迁移旧 sites-blacklist,重建
  delete config['sites-blacklist']
  delete config['sites-whitelist']
  let blacklist =
    config.blacklist && typeof config.blacklist === 'object' && !Array.isArray(config.blacklist)
      ? config.blacklist
      : {}
  delete blacklist.sites

  if (state.filterMode === 'whitelist') {
    config['sites-whitelist'] = state.sites.filter((r) => r.enabled).map((r) => r.key)
  } else if (state.filterMode === 'blacklist') {
    const bl = state.sites.filter((r) => !r.enabled).map((r) => r.key)
    if (bl.length) blacklist.sites = bl
  }

  const disabledParses = state.parses.filter((p) => !p.enabled && !p.isCustom).map((p) => p.name)
  if (disabledParses.length) blacklist.parses = disabledParses
  else delete blacklist.parses

  if (Object.keys(blacklist).length) config.blacklist = blacklist
  else delete config.blacklist

  // 自定义解析
  const customParses = state.parses.filter((p) => p.isCustom).map(buildCustomParse)
  setArrOrDelete(config, 'parses', customParses)

  // 基础
  setOrDelete(config, 'wall', state.wall)
  setOrDelete(config, 'spider', state.spider)
  setArrOrDelete(config, 'flags', state.flags)
  setArrOrDelete(config, 'ads', state.ads)

  return config
}
```

- [ ] **Step 4: 运行测试通过**

Run: `cd web-ui && node --test src/utils/subscriptionConfig.test.mjs`
Expected: 全部 PASS。

- [ ] **Step 5: 加 test 脚本**

`web-ui/package.json` 的 `"scripts"` 加一行(放在 `lint` 后):

```json
    "test": "node --test src/**/*.test.mjs"
```

(若 shell 不展开 glob,改用 `node --test src/utils/subscriptionConfig.test.mjs src/views/SubscriptionsView.test.mjs`。)

- [ ] **Step 6: Commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/utils/subscriptionConfig.test.mjs web-ui/package.json
git commit -m "feat: add pure subscription config transform module with tests"
```

---

## Phase 4 — 前端:编辑器组件

### Task 4: `SubscriptionConfigEditor.vue`

**Files:**
- Create: `web-ui/src/components/SubscriptionConfigEditor.vue`

> 该组件只做视图 + 调用 Task 3 的纯模块。状态 `state` 由 catalog(`GET /api/subscriptions/{referenceSid}/catalog`)与 `parseOverride(modelValue)` 合并而来。

- [ ] **Step 1: 创建组件**

新建 `web-ui/src/components/SubscriptionConfigEditor.vue`:

```vue
<template>
  <div class="sub-config-editor">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 站点 -->
      <el-tab-pane label="站点" name="sites">
        <el-form-item label="过滤模式" v-if="mode === 'subscription'">
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">继承全局</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过滤模式" v-else>
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">不过滤</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-alert v-if="catalogError" type="warning" :closable="false" :title="catalogError" style="margin-bottom: 8px" />

        <el-table v-if="siteRows.length" :data="siteRows" id="sub-sites-table" border style="width: 100%">
          <el-table-column label="" width="40" v-if="state.filterMode !== 'none'">
            <template #default="scope">
              <el-checkbox v-model="scope.row.enabled" :label="filterCheckboxLabel" :title="filterCheckboxLabel" />
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="scope">{{ originLabel(scope.row.origin) }}</template>
          </el-table-column>
          <el-table-column prop="key" label="key" width="160" />
          <el-table-column label="名称">
            <template #default="scope">
              <el-input v-model="scope.row.name" :disabled="scope.row.origin !== 'upstream'" />
            </template>
          </el-table-column>
          <el-table-column label="排序" width="120">
            <template #default="scope">
              <el-input v-model="scope.row.order" :disabled="scope.row.origin !== 'upstream'" placeholder="默认" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="scope">
              <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomSite(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无站点目录,可手动添加自定义站点" />

        <el-button type="primary" plain @click="openSiteForm" style="margin-top: 8px">+ 添加自定义站点</el-button>
      </el-tab-pane>

      <!-- 解析 -->
      <el-tab-pane label="解析" name="parses">
        <el-table v-if="parseRows.length" :data="parseRows" border style="width: 100%">
          <el-table-column label="启用" width="60">
            <template #default="scope">
              <el-checkbox v-model="scope.row.enabled" :disabled="scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="名称">
            <template #default="scope">
              <el-input v-model="scope.row.name" :disabled="!scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="160">
            <template #default="scope">
              <el-select v-if="scope.row.isCustom" v-model="scope.row.type">
                <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="scope">
              <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomParse(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无解析目录" />
        <el-button type="primary" plain @click="openParseForm" style="margin-top: 8px">+ 添加自定义解析</el-button>
      </el-tab-pane>

      <!-- 基础 -->
      <el-tab-pane label="基础" name="basic">
        <el-form label-width="120">
          <el-form-item label="壁纸 wall">
            <el-input v-model="state.wall" placeholder="壁纸图片/接口 URL" />
          </el-form-item>
          <el-form-item label="Spider">
            <el-input v-model="state.spider" :disabled="mode === 'global'" :placeholder="mode === 'global' ? '全局配置不支持 spider' : 'jar 地址'" />
          </el-form-item>
          <el-form-item label="播放标识 flags">
            <el-select v-model="state.flags" multiple filterable allow-create default-first-option placeholder="追加到上游" style="width: 100%" />
          </el-form-item>
          <el-form-item label="广告 ads">
            <el-select v-model="state.ads" multiple filterable allow-create default-first-option placeholder="广告域名(追加)" style="width: 100%" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 原始 JSON -->
      <el-tab-pane label="原始JSON" name="json">
        <el-input v-model="jsonText" type="textarea" :rows="18" />
        <el-alert v-if="jsonError" type="error" :closable="false" :title="jsonError" style="margin-top: 8px" />
        <div style="margin-top: 8px">
          <el-button @click="applyJson">从 JSON 应用到表单</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 自定义站点表单 -->
    <el-dialog v-model="siteFormVisible" title="自定义站点" width="640px" append-to-body destroy-on-close>
      <el-form :model="siteForm" label-width="120">
        <el-form-item label="key" required><el-input v-model="siteForm.key" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="siteForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="siteForm.type">
            <el-option v-for="o in siteTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="api"><el-input v-model="siteForm.api" /></el-form-item>
        <el-form-item label="ext"><el-input v-model="siteForm.ext" type="textarea" :rows="3" placeholder="字符串或 JSON 对象" /></el-form-item>
        <el-form-item label="jar"><el-input v-model="siteForm.jar" /></el-form-item>
        <el-form-item label="searchable">
          <el-select v-model="siteForm.searchable">
            <el-option :value="0" label="不可搜索(0)" />
            <el-option :value="1" label="可搜索(1)" />
            <el-option :value="2" label="聚合(2)" />
          </el-select>
        </el-form-item>
        <el-form-item label="quickSearch"><el-switch v-model="siteForm.quickSearch" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="filterable"><el-switch v-model="siteForm.filterable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="changeable"><el-switch v-model="siteForm.changeable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="风格 style">
          <el-select v-model="siteForm.styleType" style="width: 140px">
            <el-option value="" label="默认" />
            <el-option value="rect" label="rect" />
            <el-option value="oval" label="oval" />
            <el-option value="list" label="list" />
          </el-select>
          <el-input v-model="siteForm.styleRatio" placeholder="ratio" style="width: 120px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="排序 order"><el-input v-model="siteForm.order" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSiteForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 自定义解析表单 -->
    <el-dialog v-model="parseFormVisible" title="自定义解析" width="560px" append-to-body destroy-on-close>
      <el-form :model="parseForm" label-width="100">
        <el-form-item label="名称" required><el-input v-model="parseForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="parseForm.type">
            <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="url"><el-input v-model="parseForm.url" /></el-form-item>
        <el-form-item label="flag">
          <el-select v-model="parseForm.flag" multiple filterable allow-create default-first-option style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="parseFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmParseForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  parseOverride,
  detectFilterMode,
  disabledSiteKeys,
  whitelistKeys,
  disabledParseNames,
  siteOverrideMap,
  serialize,
  stringify,
} from '@/utils/subscriptionConfig.mjs'

const props = withDefaults(
  defineProps<{
    modelValue: string
    mode?: 'subscription' | 'global'
    referenceSid?: string
    token?: string
  }>(),
  { mode: 'subscription', referenceSid: '', token: '' }
)
const emit = defineEmits<{ 'update:modelValue': [string] }>()

const siteTypeOptions = [
  { value: 0, label: 'CMS(xml) 0' },
  { value: 1, label: 'CMS(json) 1' },
  { value: 3, label: 'Spider 3' },
  { value: 4, label: '外部 4' },
]
const parseTypeOptions = [
  { value: 0, label: '嗅探 0' },
  { value: 1, label: 'Json 1' },
  { value: 2, label: 'Json扩展 2' },
  { value: 3, label: '聚合 3' },
  { value: 4, label: '超级解析 4' },
]

const activeTab = ref('sites')
const catalogError = ref('')
const jsonText = ref('')
const jsonError = ref('')
const siteFormVisible = ref(false)
const parseFormVisible = ref(false)

const state = reactive<any>({
  filterMode: 'none',
  sites: [],
  parses: [],
  wall: '',
  spider: '',
  flags: [],
  ads: [],
})
// 未建模键的保留载体
let baseConfig: Record<string, any> = {}

const filterCheckboxLabel = '保留'
const originLabel = (o: string) => (o === 'builtin' ? '内置' : o === 'plugin' ? '插件' : '上游')
const siteRows = ref<any[]>([])
const parseRows = ref<any[]>([])

const siteForm = reactive<any>({})
const parseForm = reactive<any>({})

function resetSiteForm() {
  Object.assign(siteForm, {
    key: '', name: '', type: 3, api: '', ext: '', jar: '',
    searchable: 1, quickSearch: 1, filterable: 1, changeable: 0,
    styleType: '', styleRatio: '', order: '',
  })
}
function resetParseForm() {
  Object.assign(parseForm, { name: '', type: 0, url: '', flag: [] })
}

const load = async () => {
  const parsed = parseOverride(props.modelValue)
  baseConfig = parsed === null ? {} : JSON.parse(JSON.stringify(parsed))
  if (parsed === null) {
    jsonError.value = '原始内容不是合法 JSON,已切到 JSON 标签'
    jsonText.value = props.modelValue
    activeTab.value = 'json'
  }
  const config = parsed === null ? {} : parsed

  state.filterMode = props.mode === 'global' && detectFilterMode(config) === 'none' ? 'none' : detectFilterMode(config)
  state.wall = config.wall || ''
  state.spider = config.spider || ''
  state.flags = Array.isArray(config.flags) ? [...config.flags] : []
  state.ads = Array.isArray(config.ads) ? [...config.ads] : []

  // catalog
  let catalog: any = { sites: [], parses: [] }
  catalogError.value = ''
  if (props.referenceSid !== '' && props.referenceSid != null) {
    try {
      const { data } = await axios.get(`/api/subscriptions/${props.referenceSid}/catalog`)
      catalog = data
    } catch {
      catalogError.value = '获取站点目录失败,可手动添加自定义站点'
    }
  }
  buildRows(config, catalog)
}

function buildRows(config: Record<string, any>, catalog: any) {
  const disabled = new Set(disabledSiteKeys(config))
  const white = new Set(whitelistKeys(config))
  const overrides = siteOverrideMap(config)
  const catalogKeys = new Set<string>((catalog.sites || []).map((s: any) => String(s.key)))

  const rows: any[] = []
  for (const c of catalog.sites || []) {
    const key = String(c.key)
    const ov = overrides[key] || {}
    rows.push({
      key,
      origin: c.origin,
      isCustom: false,
      enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
      name: ov.name != null ? ov.name : c.name,
      originalName: c.name,
      hadNameOverride: ov.name != null,
      order: ov.order != null ? ov.order : '',
    })
  }
  // catalog 缺失但被禁用/白名单引用的 key -> 合成行
  const known = new Set(rows.map((r) => r.key))
  ;[...disabled, ...white].forEach((key) => {
    if (!known.has(key)) {
      const ov = overrides[key] || {}
      rows.push({
        key, origin: 'upstream', isCustom: false,
        enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
        name: ov.name != null ? ov.name : key, originalName: key, hadNameOverride: ov.name != null,
        order: ov.order != null ? ov.order : '',
      })
      known.add(key)
    }
  })
  // 自定义站点(config.sites 中 key ∉ catalog)
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      const key = s && s.key != null ? String(s.key) : ''
      if (!key || catalogKeys.has(key) || known.has(key)) continue
      // 仅当是“完整自定义”(有 type 或 api)才算自定义站点;否则当上游 override(已在上面处理)
      if (s.type != null || s.api != null) {
        rows.push({
          ...s, key, origin: 'custom', isCustom: true, enabled: true,
          styleType: s.style?.type || '', styleRatio: s.style?.ratio ?? '',
        })
        known.add(key)
      }
    }
  }
  siteRows.value = rows
  state.sites = rows

  // 解析
  const disabledParses = new Set(disabledParseNames(config))
  const prows: any[] = []
  for (const p of catalog.parses || []) {
    prows.push({ name: p.name, isCustom: false, enabled: !disabledParses.has(p.name) })
  }
  if (Array.isArray(config.parses)) {
    for (const p of config.parses) {
      if (p && p.name) {
        prows.push({
          name: p.name, isCustom: true, enabled: true, type: p.type ?? 0,
          url: p.url || '', flag: p.ext?.flag || [], header: p.ext?.header || {},
        })
      }
    }
  }
  parseRows.value = prows
  state.parses = prows
}

function openSiteForm() {
  resetSiteForm()
  siteFormVisible.value = true
}
function confirmSiteForm() {
  if (!siteForm.key) {
    ElMessage.warning('请输入 key')
    return
  }
  const row: any = {
    key: siteForm.key, name: siteForm.name, type: siteForm.type, api: siteForm.api,
    ext: siteForm.ext, jar: siteForm.jar, searchable: siteForm.searchable,
    quickSearch: siteForm.quickSearch, filterable: siteForm.filterable, changeable: siteForm.changeable,
    order: siteForm.order, origin: 'custom', isCustom: true, enabled: true,
  }
  if (siteForm.styleType) row.style = { type: siteForm.styleType, ratio: Number(siteForm.styleRatio) || undefined }
  // ext 文本若是 JSON 对象则解析
  if (typeof row.ext === 'string' && row.ext.trim().startsWith('{')) {
    try { row.ext = JSON.parse(row.ext) } catch { /* keep string */ }
  }
  siteRows.value.push(row)
  state.sites = siteRows.value
  siteFormVisible.value = false
}
function removeCustomSite(row: any) {
  siteRows.value = siteRows.value.filter((r) => r !== row)
  state.sites = siteRows.value
}

function openParseForm() {
  resetParseForm()
  parseFormVisible.value = true
}
function confirmParseForm() {
  if (!parseForm.name) {
    ElMessage.warning('请输入名称')
    return
  }
  parseRows.value.push({
    name: parseForm.name, type: parseForm.type, url: parseForm.url,
    flag: [...parseForm.flag], header: {}, isCustom: true, enabled: true,
  })
  state.parses = parseRows.value
  parseFormVisible.value = false
}
function removeCustomParse(row: any) {
  parseRows.value = parseRows.value.filter((r) => r !== row)
  state.parses = parseRows.value
}

function onTabChange(name: string) {
  if (name === 'json') {
    jsonText.value = JSON.stringify(serialize(baseConfig, state), null, 2)
    jsonError.value = ''
  }
}
function applyJson() {
  const parsed = parseOverride(jsonText.value)
  if (parsed === null) {
    jsonError.value = 'JSON 格式错误'
    return
  }
  baseConfig = JSON.parse(JSON.stringify(parsed))
  jsonError.value = ''
  // 用当前 catalog 重新构建(catalog 不变,重新读取 props.referenceSid 的缓存行的 key 集合)
  const catalog = { sites: siteRows.value.filter((r) => !r.isCustom).map((r) => ({ key: r.key, name: r.originalName, origin: r.origin })), parses: parseRows.value.filter((p) => !p.isCustom).map((p) => ({ name: p.name })) }
  // 基础字段也同步
  state.wall = parsed.wall || ''
  state.spider = parsed.spider || ''
  state.flags = Array.isArray(parsed.flags) ? [...parsed.flags] : []
  state.ads = Array.isArray(parsed.ads) ? [...parsed.ads] : []
  state.filterMode = detectFilterMode(parsed)
  buildRows(parsed, catalog)
  ElMessage.success('已应用到表单')
}

// 对外:保存时取序列化结果
function getValue(): string {
  return stringify(serialize(baseConfig, state))
}
defineExpose({ getValue, reload: load })

watch(
  () => [props.modelValue, props.referenceSid],
  () => load(),
  { immediate: true }
)
</script>

<style scoped>
.sub-config-editor {
  min-height: 420px;
}
</style>
```

- [ ] **Step 2: 类型检查**

Run: `cd web-ui && npx vue-tsc --noEmit`
Expected: 无与本组件相关的类型错误(`.mjs` 无类型,以 any 引入;若报模块声明缺失,见下步)。

- [ ] **Step 3:(如需)为 `.mjs` 声明模块**

若 `vue-tsc` 报 `Cannot find module '@/utils/subscriptionConfig.mjs'`,在 `web-ui/src` 下新建 `subscriptionConfig.d.ts`:

```ts
declare module '@/utils/subscriptionConfig.mjs' {
  export function parseOverride(text: string): Record<string, any> | null
  export function stringify(config: Record<string, any>): string
  export function detectFilterMode(config: Record<string, any>): 'none' | 'whitelist' | 'blacklist'
  export function disabledSiteKeys(config: Record<string, any>): string[]
  export function whitelistKeys(config: Record<string, any>): string[]
  export function disabledParseNames(config: Record<string, any>): string[]
  export function siteOverrideMap(config: Record<string, any>): Record<string, { name?: any; order?: any }>
  export function customSites(config: Record<string, any>, catalogKeys: string[]): any[]
  export function serialize(baseConfig: Record<string, any>, state: any): Record<string, any>
}
```

Run again: `cd web-ui && npx vue-tsc --noEmit` → 通过。

- [ ] **Step 4: Commit**

```bash
git add web-ui/src/components/SubscriptionConfigEditor.vue web-ui/src/subscriptionConfig.d.ts
git commit -m "feat: add SubscriptionConfigEditor visual editor component"
```

---

## Phase 5 — 前端:接入 SubscriptionsView

### Task 5: 订阅编辑对话框 + 全局配置对话框接入

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: 引入组件 + 新增状态**

在 `<script setup>` 顶部 import 区加:

```ts
import SubscriptionConfigEditor from "@/components/SubscriptionConfigEditor.vue";
```

在 ref 定义区加:

```ts
const editorVisible = ref(false)
const editorRef = ref<any>(null)
const editorTargetIsGlobal = ref(false)
const globalReferenceSid = ref('')
```

- [ ] **Step 2: 订阅编辑对话框 —— 用按钮打开编辑器**

把订阅表单里「定制」那段:

```html
        <el-form-item label="定制" label-width="140">
          <el-input v-model="form.override" type="textarea" rows="15"/>
          <a href="https://www.json.cn/" target="_blank">JSON验证</a>
        </el-form-item>
```

替换为:

```html
        <el-form-item label="定制" label-width="140">
          <el-button @click="openEditor(false)">🎨 可视化编辑</el-button>
          <span class="hint">{{ form.override ? '已配置' : '未配置' }}</span>
        </el-form-item>
```

同时删除订阅表单里旧的「站点过滤模式 / 白名单站点 / 黑名单站点」三个 `el-form-item`(`sitesFilterMode`/`sitesWhitelist`/`sitesBlacklist`),以及 `handleAdd`/`handleEdit`/`handleConfirm` 中与 `sitesFilterMode`/`sitesWhitelist`/`sitesBlacklist` 相关的解析与合并代码(过滤逻辑改由编辑器写入 `form.override`)。具体:
- `handleAdd`:删除 `sitesFilterMode.value='none'`、`sitesWhitelist.value=''`、`sitesBlacklist.value=''` 三行。
- `handleEdit`:删除「解析 override 中的白名单/黑名单」整段(`if (data.override) { try {...} catch {} }`)及其上的三行重置。
- `handleConfirm`:删除「合并白名单/黑名单到 override」整段,直接 `axios.post('/api/subscriptions', form.value)...`。

- [ ] **Step 3: 加编辑器对话框(订阅 + 全局共用)**

在模板末尾 `</div>` 前新增:

```html
    <el-dialog v-model="editorVisible" :title="editorTargetIsGlobal ? '全局订阅配置' : '订阅定制'" width="900px" destroy-on-close>
      <div v-if="editorTargetIsGlobal" style="margin-bottom: 8px">
        参考订阅：
        <el-select v-model="globalReferenceSid" style="width: 220px">
          <el-option v-for="item in subscriptions" :key="item.sid" :label="item.name" :value="item.sid" />
        </el-select>
      </div>
      <SubscriptionConfigEditor
        ref="editorRef"
        :model-value="editorTargetIsGlobal ? globalConfigJson : form.override"
        :mode="editorTargetIsGlobal ? 'global' : 'subscription'"
        :reference-sid="editorTargetIsGlobal ? globalReferenceSid : form.sid"
        :token="token"
      />
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEditor">{{ editorTargetIsGlobal ? '保存全局' : '应用到定制' }}</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 4: 编辑器打开/保存方法**

在 `<script setup>` 加:

```ts
const openEditor = (isGlobal: boolean) => {
  editorTargetIsGlobal.value = isGlobal
  if (isGlobal) {
    globalReferenceSid.value = subscriptions.value.length ? (subscriptions.value[0] as any).sid : ''
    loadGlobalConfig()
  }
  editorVisible.value = true
}

const saveEditor = () => {
  const value = editorRef.value?.getValue() ?? ''
  if (editorTargetIsGlobal.value) {
    let config: any = {}
    try { config = value ? JSON.parse(value) : {} } catch { ElMessage.error('JSON格式错误'); return }
    axios.put('/api/subscriptions/global-config', config).then(() => {
      ElMessage.success('全局配置保存成功')
      editorVisible.value = false
    })
  } else {
    form.value.override = value
    editorVisible.value = false
  }
}
```

- [ ] **Step 5: 「全局配置」按钮改为打开编辑器**

把 `showGlobalConfig` 改为:

```ts
const showGlobalConfig = () => {
  openEditor(true)
}
```

删除旧的全局配置对话框(`<el-dialog v-model="globalConfigVisible" ...>` 整段)与 `saveGlobalConfig`、`globalConfigVisible`/`globalFilterMode`/`globalWhitelist`/`globalBlacklist` 相关代码。保留 `globalConfigJson` 与 `loadGlobalConfig`(编辑器用 `globalConfigJson` 作为 modelValue);把 `loadGlobalConfig` 精简为:

```ts
const loadGlobalConfig = () => {
  axios.get('/api/subscriptions/global-config').then(response => {
    globalConfigJson.value = JSON.stringify(response.data || {})
  })
}
```

- [ ] **Step 6: 类型检查 + 既有视图测试**

Run: `cd web-ui && npx vue-tsc --noEmit`
Expected: 通过(若有 `sitesFilterMode` 等未用变量报错,一并删除其 `ref` 定义)。

Run: `cd web-ui && node --test src/views/SubscriptionsView.test.mjs`
Expected: 既有 5 个测试仍 PASS(它们不依赖被删字段)。

- [ ] **Step 7: 加一条视图接入断言(可选)**

在 `web-ui/src/views/SubscriptionsView.test.mjs` 末尾加:

```js
test('uses visual editor for subscription override instead of raw textarea', () => {
  assert.equal(viewSource.includes('SubscriptionConfigEditor'), true)
  assert.equal(viewSource.includes("openEditor(false)"), true)
})
```

Run: `cd web-ui && node --test src/views/SubscriptionsView.test.mjs` → PASS。

- [ ] **Step 8: Commit**

```bash
git add web-ui/src/views/SubscriptionsView.vue web-ui/src/views/SubscriptionsView.test.mjs
git commit -m "feat: wire visual config editor into subscription and global config dialogs"
```

---

## Phase 6 — 集成验证

### Task 6: 全量构建与测试

- [ ] **Step 1: 后端全量测试**

Run: `mvn -q test -Dtest=SubscriptionServiceTest`
Expected: 全部 PASS。

- [ ] **Step 2: 前端类型检查 + 单测 + 构建**

Run:
```bash
cd web-ui
npx vue-tsc --noEmit
node --test src/utils/subscriptionConfig.test.mjs src/views/SubscriptionsView.test.mjs
npm run build-only
```
Expected: 类型检查通过;测试全 PASS;`vite build` 成功(产物到 `src/main/resources/static/`)。

- [ ] **Step 3: 手动冒烟(运行应用)**

启动应用,打开订阅页:
- 编辑某订阅 → 「🎨 可视化编辑」→ 站点标签出现上游+内置+插件;黑名单模式取消勾选某内置源 → 应用 → 保存订阅 → 打开「数据」确认该站点已消失。
- 白名单模式只勾选少数站点 → 数据里仅剩这些。
- 「全局配置」→ 选参考订阅 → 配置黑名单 → 保存;另一个订阅的「数据」也应受全局影响(订阅自身无过滤时)。
- 自定义站点/解析、壁纸/flags 正确出现在「数据」JSON。
- 「原始JSON」标签编辑 rules/doh 后切回表单再保存,rules/doh 不丢。

- [ ] **Step 4: Commit(若冒烟中有微调)**

```bash
git add -A
git commit -m "chore: polish subscription visual config editor"
```

---

## Self-Review(对照 spec)

- §4.1 真值表 → Task 1(9 个用例覆盖全部分支 + Alist1 + parses + 白名单忽略 parses)。✓
- §4.1 删死代码 / 静态化 → Task 1 Step 3-4。✓
- §4.3 catalog 端点(三类 origin + push_agent 映射 + 不套 override/过滤)→ Task 2。✓
- §5.5 读写映射 / 未知键保留 / 迁移 sites-blacklist → Task 3(serialize/parse + 用例)。✓
- §5.1–5.4 编辑器(四标签 + 内置/插件 名称排序禁用 + 自定义表单 + JSON 往返)→ Task 4。✓
- §5.1 复用(单订阅按钮 + 全局内嵌 + 参考订阅下拉)→ Task 5。✓
- §8 测试矩阵(后端真值表/catalog;前端往返/保留未知键/迁移)→ Task 1/2/3 + Task 5 Step 7。✓
- 占位符扫描:无 TODO/TBD;所有步骤含可运行命令与完整代码。✓
- 命名一致性:`resolveAndApplyFilters`/`buildCatalog`/`getCatalog`/`serialize`/`stringify`/`parseOverride`/`detectFilterMode` 在前后端各 Task 间一致。✓

## 未决/执行期注意

- `node --test src/**/*.test.mjs` 的 glob 由 shell 展开;若 CI/本地 shell 不展开,用显式文件列表(已在 Task 3 Step 5 备注)。
- `getCatalog` 会联网解析上游 URL,首次打开编辑器可能稍慢;失败时前端已降级为「仅自定义」。
- 行为变更:黑名单/继承模式现在会移除 `Alist1` 站点(`removeBlacklist` 既有逻辑),已在 Task 1 用例固化——如不希望,执行期反馈。
