# 订阅定制保留所有 JSON 字段 + homePage 支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 订阅定制编辑器 read-edit-write 循环保留全部用户输入字段(含未知字段),且 `homePage` 有 GUI 入口。

**Architecture:** 纯前端。新增纯函数 `pickExtra(obj, modeledKeys)` → 行上挂 `_extra`;每个 load builder 捕获 `_extra`,每个 serialize builder 发射 `{..._extra, ...modeled}`(建模字段权威)。custom site 改为「发射除内部 UI 键外所有键」。`homePage` 加入站点建模键表 + GUI。

**Tech Stack:** Vue 3 + TS + Element Plus;纯转换逻辑在 `web-ui/src/utils/subscriptionConfig.mjs`,`node:test`。

**Run tests:** `cd web-ui && node --test src/utils/subscriptionConfig.test.mjs` (全量 `npm test`)

**Spec:** `docs/superpowers/specs/2026-06-13-subscription-preserve-all-json-fields-design.md`

---

## File Structure

- Modify `web-ui/src/utils/subscriptionConfig.mjs` — 加 `pickExtra`;改 `buildCustomSite`/`buildAdvancedOverride`/`buildCustomParse`/`buildHeaderItem`+`buildHeaderRows`/`buildLiveItem`+`buildLiveRows`+`normalizeGroup`+`normalizeChannel`+`buildGroupsArray`+`buildChannelItem`/serialize 内 doh·proxy·rules 内联块;`ADVANCED_OVERRIDE_KEYS` 加 `homePage`。
- Modify `web-ui/src/utils/subscriptionConfig.d.ts` — 导出 `pickExtra`。
- Modify `web-ui/src/utils/subscriptionConfig.test.mjs` — 各 section round-trip 测试。
- Modify `web-ui/src/components/SubscriptionConfigEditor.vue` — `buildRows` 捕获 `_extra`;`homePage` GUI(自定义表单 + 高级弹窗 + reset);import `pickExtra`。

---

### Task 1: `pickExtra` 纯函数

**Files:**
- Modify: `web-ui/src/utils/subscriptionConfig.mjs` (在 `stringify` 后插入)
- Modify: `web-ui/src/utils/subscriptionConfig.d.ts`
- Modify: `web-ui/src/utils/subscriptionConfig.test.mjs` (import + 测试)

- [ ] **Step 1: 写失败测试**

`subscriptionConfig.test.mjs` import 列表加 `pickExtra`;文件末尾追加:
```js
test('pickExtra: returns unmodeled keys only', () => {
  assert.deepEqual(pickExtra({ a: 1, b: 2, c: 3 }, ['a', 'c']), { b: 2 })
  assert.deepEqual(pickExtra({}, ['a']), {})
  assert.deepEqual(pickExtra(null, ['a']), {})
  assert.deepEqual(pickExtra([1, 2], ['a']), {})
})
```

- [ ] **Step 2: 跑,确认失败**

`cd web-ui && node --test src/utils/subscriptionConfig.test.mjs` → FAIL(`pickExtra is not a function`)。

- [ ] **Step 3: 实现**

`subscriptionConfig.mjs` 在 `stringify`(line 17)后插入:
```js
export function pickExtra(obj, modeledKeys) {
  const extra = {}
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return extra
  const set = new Set(modeledKeys)
  for (const k of Object.keys(obj)) {
    if (!set.has(k)) extra[k] = obj[k]
  }
  return extra
}
```

`subscriptionConfig.d.ts` 加(line 15 前):
```ts
  export function pickExtra(obj: Record<string, any>, modeledKeys: string[]): Record<string, any>
```

- [ ] **Step 4: 跑,确认通过**

`node --test src/utils/subscriptionConfig.test.mjs` → PASS(32 tests)。

- [ ] **Step 5: commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/utils/subscriptionConfig.d.ts web-ui/src/utils/subscriptionConfig.test.mjs
git commit -m "feat: 订阅定制 pickExtra 纯函数"
```

---

### Task 2: custom site 保留全部字段 + homePage

**Files:**
- Modify: `web-ui/src/utils/subscriptionConfig.mjs` (`CUSTOM_SITE_KEYS` line 63-67 + `buildCustomSite` 69-77)
- Modify: `web-ui/src/utils/subscriptionConfig.test.mjs`

- [ ] **Step 1: 写失败测试**

末尾追加:
```js
test('serialize: custom site preserves unknown fields + homePage, no internal leak', () => {
  const state = {
    ...defaultState(),
    sites: [{
      key: 'mine', origin: 'custom', enabled: true, isCustom: true,
      name: '自定义', type: 3, api: 'csp_X',
      homePage: 'http://h/6v.html', hide: 1, extensions: { a: 1 },
      originalName: '自定义', styleType: '', styleRatio: '',
    }],
  }
  const out = serialize({}, state)
  const s = out.sites.find((x) => x.key === 'mine')
  assert.equal(s.homePage, 'http://h/6v.html')
  assert.equal(s.hide, 1)
  assert.deepEqual(s.extensions, { a: 1 })
  assert.equal(s.styleType, undefined)
  assert.equal(s.isCustom, undefined)
  assert.equal(s.enabled, undefined)
})
```

- [ ] **Step 2: 跑,确认失败(当前 `hide`/`homePage`/`extensions` 被丢)**

`node --test src/utils/subscriptionConfig.test.mjs` → FAIL。

- [ ] **Step 3: 实现**

替换 `CUSTOM_SITE_KEYS`(63-67)+ `buildCustomSite`(69-77)为:
```js
const SITE_INTERNAL_KEYS = new Set([
  'isCustom', 'origin', 'enabled', 'originalName', 'hadNameOverride',
  'hasAdvancedOverride', 'styleType', 'styleRatio', '_extra',
])

function buildCustomSite(row) {
  const s = {}
  for (const k of Object.keys(row)) {
    if (SITE_INTERNAL_KEYS.has(k)) continue
    const v = row[k]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    s[k] = v
  }
  return s
}
```

- [ ] **Step 4: 跑,确认通过**

`node --test src/utils/subscriptionConfig.test.mjs` → PASS。回归:既有 custom site 测试(order/stringify 等)仍过。

- [ ] **Step 5: commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/utils/subscriptionConfig.test.mjs
git commit -m "feat: 自定义站点保留全部字段"
```

---

### Task 3: non-custom site `_extra` 透传 + homePage

**Files:**
- Modify: `web-ui/src/utils/subscriptionConfig.mjs` (`ADVANCED_OVERRIDE_KEYS` 299-302 + serialize 非自定义分支 322-332)
- Modify: `web-ui/src/components/SubscriptionConfigEditor.vue` (import + `buildRows` 捕获 `_extra` + inline `ADVANCED_KEYS`)
- Modify: `web-ui/src/utils/subscriptionConfig.test.mjs`

- [ ] **Step 1: 写失败测试**

末尾追加:
```js
test('serialize: non-custom site preserves _extra and emits homePage', () => {
  const state = {
    ...defaultState(),
    sites: [{
      key: 'a', origin: 'upstream', enabled: true, isCustom: false,
      name: 'A', originalName: 'A', order: '',
      _extra: { hide: 1, extensions: { x: 1 } },
      hasAdvancedOverride: true, homePage: 'http://h/6v.html', searchable: 1,
    }],
  }
  const out = serialize({}, state)
  const s = out.sites.find((x) => x.key === 'a')
  assert.equal(s.homePage, 'http://h/6v.html')
  assert.equal(s.hide, 1)
  assert.deepEqual(s.extensions, { x: 1 })
})

test('serialize: non-custom site with only _extra is still emitted', () => {
  const state = {
    ...defaultState(),
    sites: [{
      key: 'b', origin: 'builtin', enabled: true, isCustom: false,
      name: 'B', originalName: 'B', order: '', _extra: { hide: 1 },
    }],
  }
  const out = serialize({}, state)
  assert.equal(out.sites.find((x) => x.key === 'b').hide, 1)
})
```

- [ ] **Step 2: 跑,确认失败**

`node --test src/utils/subscriptionConfig.test.mjs` → FAIL(`homePage`/`hide` 未发)。

- [ ] **Step 3: 实现 .mjs**

`ADVANCED_OVERRIDE_KEYS`(299-302)末尾加 `'homePage'`:
```js
const ADVANCED_OVERRIDE_KEYS = [
  'ext', 'searchable', 'quickSearch', 'filterable', 'changeable',
  'style', 'timeout', 'indexs', 'playUrl', 'click', 'categories', 'header', 'homePage',
]
```

serialize 非自定义分支(原 322-332)改为:
```js
    } else {
      const o = { ...(row._extra || {}) }
      if (row.name && row.name !== row.originalName) o.name = row.name
      else if (row.hadNameOverride && row.name) o.name = row.name
      if (row.order !== '' && row.order !== null && row.order !== undefined) o.order = Number(row.order)
      if (row.hasAdvancedOverride) Object.assign(o, buildAdvancedOverride(row))
      if (Object.keys(o).length) {
        o.key = row.key
        sites.push(o)
      }
    }
```

- [ ] **Step 4: 跑 .mjs 测试,确认通过**

`node --test src/utils/subscriptionConfig.test.mjs` → PASS。

- [ ] **Step 5: 实现 .vue 捕获 `_extra`**

`SubscriptionConfigEditor.vue`:
1. import 块(约 580 行 `siteOverrideMap,` 处)加 `pickExtra,`。
2. `buildRows` 内 inline `ADVANCED_KEYS`(726-729)末尾加 `'homePage'`:
```js
  const ADVANCED_KEYS = [
    'ext', 'searchable', 'quickSearch', 'filterable', 'changeable',
    'style', 'timeout', 'indexs', 'playUrl', 'click', 'categories', 'header', 'homePage',
  ]
```
3. catalog 循环里 `applyAdvancedOverride(row, ov)`(755)之后、`rows.push(row)`(756)之前插:
```js
    row._extra = pickExtra(ov, [...ADVANCED_KEYS, 'name', 'order'])
```
4. 合成 key 循环里 `applyAdvancedOverride(row, ov)`(769)之后、`rows.push(row)`(770)之前插同样一行。

- [ ] **Step 6: commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/components/SubscriptionConfigEditor.vue web-ui/src/utils/subscriptionConfig.test.mjs
git commit -m "feat: 非自定义站点 raw 字段透传 + homePage"
```

---

### Task 4: parse / headers / doh / proxy / rules 透传

**Files:**
- Modify: `web-ui/src/utils/subscriptionConfig.mjs` (`buildCustomParse`, `buildHeaderRows`+`buildHeaderItem`, `buildDohRows`+serialize doh 块, `buildProxyRows`+serialize proxy 块, `buildRulesRows`+serialize rules 块)
- Modify: `web-ui/src/components/SubscriptionConfigEditor.vue` (`buildRows` parse 循环捕获 `_extra`)
- Modify: `web-ui/src/utils/subscriptionConfig.test.mjs`

- [ ] **Step 1: 写失败测试**

末尾追加:
```js
test('serialize: custom parse preserves _extra', () => {
  const out = serialize({}, { ...defaultState(), parses: [
    { name: 'p', isCustom: true, enabled: true, type: 0, url: 'http://u', flag: [], header: {}, _extra: { ext2: 'x' } },
  ] })
  assert.equal(out.parses[0].ext2, 'x')
  assert.equal(out.parses[0].name, 'p')
})

test('headers round-trip unknown fields', () => {
  const rows = buildHeaderRows({ headers: [{ host: 'a.com', header: { X: '1' }, timeout: 5 }] })
  assert.equal(rows[0]._extra.timeout, 5)
  const out = serialize({}, { ...defaultState(), headers: rows })
  assert.equal(out.headers[0].timeout, 5)
  assert.equal(out.headers[0].host, 'a.com')
})

test('doh round-trip unknown fields', () => {
  const rows = buildDohRows({ doh: [{ name: 'G', url: 'u', ips: [], remark: 'r' }] })
  assert.equal(rows[0]._extra.remark, 'r')
  const out = serialize({}, { ...defaultState(), doh: rows })
  assert.equal(out.doh[0].remark, 'r')
})

test('proxy round-trip unknown fields', () => {
  const rows = buildProxyRows({ proxy: [{ name: 'P', hosts: ['x.com'], urls: [], remark: 'r' }] })
  const out = serialize({}, { ...defaultState(), proxy: rows })
  assert.equal(out.proxy[0].remark, 'r')
})

test('rules round-trip unknown fields', () => {
  const rows = buildRulesRows({ rules: [{ name: 'R', hosts: [], regex: [], script: [], exclude: [], enabled: true }] })
  const out = serialize({}, { ...defaultState(), rules: rows })
  assert.equal(out.rules[0].enabled, true)
})
```
注:`buildHeaderRows`/`buildDohRows`/`buildProxyRows`/`buildRulesRows` 已在 test import 中。

- [ ] **Step 2: 跑,确认失败**

`node --test src/utils/subscriptionConfig.test.mjs` → FAIL。

- [ ] **Step 3: 实现 .mjs**

`buildCustomParse`(238-247)首行改:
```js
function buildCustomParse(row) {
  const p = { ...(row._extra || {}), name: row.name }
  if (row.type != null && row.type !== '') p.type = Number(row.type)
  if (row.url) p.url = row.url
  const ext = {}
  if (Array.isArray(row.flag) && row.flag.length) ext.flag = row.flag
  if (row.header && Object.keys(row.header).length) ext.header = row.header
  if (Object.keys(ext).length) p.ext = ext
  return p
}
```

`buildHeaderRows` 的 `.map(...)`(88-94)对象加 `_extra`:
```js
    .map((h) => ({
      host: h.host || '',
      pairs: h.header && typeof h.header === 'object'
        ? Object.entries(h.header).map(([name, value]) => ({ name, value: String(value) }))
        : [],
      _extra: pickExtra(h, ['host', 'header']),
    }))
```

`buildHeaderItem`(97-101)改为:
```js
function buildHeaderItem(row) {
  const header = {}
  for (const p of row.pairs || []) {
    if (p.name) header[p.name] = p.value || ''
  }
  const item = { ...(row._extra || {}) }
  if (row.host) item.host = row.host
  if (Object.keys(header).length) item.header = header
  return Object.keys(item).length ? item : null
}
```

`buildDohRows` map 对象加 `_extra: pickExtra(d, ['name', 'url', 'ips'])`。
`buildProxyRows` map 对象加 `_extra: pickExtra(p, ['name', 'hosts', 'urls'])`。
`buildRulesRows` map 对象加 `_extra: pickExtra(r, ['name', 'hosts', 'regex', 'script', 'exclude'])`。

serialize doh 块(385-388)改:
```js
  const doh = state.doh.filter((d) => d.name || d.url).map((d) => {
    const item = { ...(d._extra || {}) }
    if (d.name) item.name = d.name
    if (d.url) item.url = d.url
    if (Array.isArray(d.ips) && d.ips.length) item.ips = [...d.ips]
    return item
  })
```
serialize proxy 块(395-398)改:加 `const item = { ...(p._extra || {}) }` 前置,name/hosts/urls 叠加。
serialize rules 块(405-408)改:加 `const item = { ...(r._extra || {}) }` 前置,各键叠加。

- [ ] **Step 4: 实现 .vue parse 捕获**

`SubscriptionConfigEditor.vue` `buildRows` parse 循环(800-803)prow 对象加:
```js
        prows.push({
          name: p.name, isCustom: true, enabled: true, type: p.type ?? 0,
          url: p.url || '', flag: p.ext?.flag || [], header: p.ext?.header || {},
          _extra: pickExtra(p, ['name', 'type', 'url', 'ext']),
        })
```

- [ ] **Step 5: 跑,确认通过**

`node --test src/utils/subscriptionConfig.test.mjs` → PASS。

- [ ] **Step 6: commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/components/SubscriptionConfigEditor.vue web-ui/src/utils/subscriptionConfig.test.mjs
git commit -m "feat: parse/headers/doh/proxy/rules 保留未知字段"
```

---

### Task 5: lives / groups / channels 透传

**Files:**
- Modify: `web-ui/src/utils/subscriptionConfig.mjs` (`buildLiveRows`, `normalizeGroup`, `normalizeChannel`, `buildLiveItem`, `buildGroupsArray`, `buildChannelItem`)
- Modify: `web-ui/src/utils/subscriptionConfig.test.mjs`

- [ ] **Step 1: 写失败测试**

末尾追加:
```js
test('lives preserve unknown live/group/channel fields', () => {
  const rows = buildLiveRows({ lives: [{
    name: 'L', url: './live.json', customField: 'lf',
    groups: [{ name: 'G', gExtra: 1, channel: [{ name: 'C', urls: ['u'], cExtra: 2 }] }],
  }] })
  assert.equal(rows[0]._extra.customField, 'lf')
  assert.equal(rows[0].groups[0]._extra.gExtra, 1)
  assert.equal(rows[0].groups[0].channels[0]._extra.cExtra, 2)
  const out = serialize({}, { ...defaultState(), lives: rows })
  assert.equal(out.lives[0].customField, 'lf')
  assert.equal(out.lives[0].groups[0].gExtra, 1)
  assert.equal(out.lives[0].groups[0].channel[0].cExtra, 2)
})
```

- [ ] **Step 2: 跑,确认失败**

`node --test src/utils/subscriptionConfig.test.mjs` → FAIL。

- [ ] **Step 3: 实现**

`normalizeChannel`(113-132)返回对象末尾加:
```js
      _extra: pickExtra(c, CHANNEL_KEYS),
```
`normalizeGroup`(105-111)返回对象末尾加:
```js
      _extra: pickExtra(g, ['name', 'pass', 'channel']),
```
`buildLiveRows` 的 `.map`(144-159)对象末尾加:
```js
      _extra: pickExtra(l, LIVE_KEYS),
```
`buildChannelItem`(173-189)改首行 `const item = {}` → `const item = { ...(c._extra || {}) }`。
`buildGroupsArray`(191-201)group item 改 `const item = {}` → `const item = { ...(g._extra || {}) }`。
`buildLiveItem`(208-234)改首行 `const item = {}` → `const item = { ...(row._extra || {}) }`。

- [ ] **Step 4: 跑,确认通过**

`node --test src/utils/subscriptionConfig.test.mjs` → PASS。回归:既有 lives round-trip 测试仍过。

- [ ] **Step 5: commit**

```bash
git add web-ui/src/utils/subscriptionConfig.mjs web-ui/src/utils/subscriptionConfig.test.mjs
git commit -m "feat: lives/groups/channels 保留未知字段"
```

---

### Task 6: homePage GUI + reset 清 `_extra` + 全量验证

**Files:**
- Modify: `web-ui/src/components/SubscriptionConfigEditor.vue` (`resetSiteForm` 669-677, `confirmSiteForm` 824-835, 自定义表单模板 ~399 后, `openSiteAdvanced` 872-887, `confirmSiteAdvanced` 898-914, 高级弹窗模板 ~476 后, `resetSiteToDefault` 848-865)

- [ ] **Step 1: 自定义站点表单加 homePage**

`resetSiteForm` 对象(670-676)加 `homePage: '',`。
`confirmSiteForm` row(824-835)加:
```js
    homePage: siteForm.homePage || undefined,
```
模板:在「Spider jar」`el-form-item`(379)后加:
```html
        <el-form-item label="首页 homePage"><el-input v-model="siteForm.homePage" placeholder="站点首页 / 推荐页 URL" /></el-form-item>
```

- [ ] **Step 2: 高级设置弹窗加 homePage**

`openSiteAdvanced` 的 `Object.assign(siteAdvancedForm, {...})`(872-887)加 `homePage: row.homePage ?? '',`。
`confirmSiteAdvanced`(898-914)在 `row.click = ...` 后加:
```js
  row.homePage = siteAdvancedForm.homePage || undefined
```
模板:在「点击拦截 click」`el-form-item`(476)后加:
```html
        <el-form-item label="首页 homePage"><el-input v-model="siteAdvancedForm.homePage" placeholder="站点首页 / 推荐页 URL" /></el-form-item>
```

- [ ] **Step 3: reset 清 `_extra` + homePage**

`resetSiteToDefault`(848-865)末尾(`row.hasAdvancedOverride = false` 前)加:
```js
  row.homePage = undefined
  row._extra = undefined
```

- [ ] **Step 4: 全量测试**

`cd web-ui && npm test` → 全 PASS(含 `SubscriptionsView.test.mjs`)。
`cd web-ui && npm run build` → 构建成功(类型/模板无误)。

- [ ] **Step 5: 手动验证**

`mvn ...` 不需要;跑前端 dev 或本地构建产物,打开订阅定制编辑器:
1. 自定义站点表单填 `homePage` → 保存 → 原始 JSON 含 `homePage`。
2. 某上游/内置站点高级设置填 `homePage` → 保存 → 原始 JSON 该站点 override 含 `homePage`。
3. 原始 JSON 手填未知字段(如某 site `hide:1`、某 live `customField:'x'`)→ 进编辑器改无关项 → 保存 → 未知字段仍在。
4. 「恢复默认」后该站点 raw 字段被清。

- [ ] **Step 6: commit**

```bash
git add web-ui/src/components/SubscriptionConfigEditor.vue
git commit -m "feat: homePage GUI 入口 + 恢复默认清 raw 字段"
```

---

## Self-Review

- **Spec coverage:** `_extra` 透传覆盖 sites(custom T2 / non-custom T3)、parses/headers/doh/proxy/rules(T4)、lives/groups/channels(T5);`homePage` 建模(GUI T6 + 键表 T2/T3);reset 清 `_extra`(T6);`pickExtra` 纯函数单测(T1);不改编译目标/backend/catalog(T6 仅前端)。✓
- **Placeholder:** 无 TBD/TODO;所有代码步骤含完整代码。✓
- **Type 一致:** `_extra` 键名、`pickExtra(obj, modeledKeys)` 签名、`SITE_INTERNAL_KEYS` 贯穿一致;`homePage` 在 ADVANCED_OVERRIDE_KEYS(.mjs)与 inline ADVANCED_KEYS(.vue)两处同步。✓
- 注:`.vue` 的 `buildRows` 捕获 `_extra`(T3 Step 5、T4 Step 4)属 UI 层,不经 node:test;由 T6 手动验证覆盖。核心 serialize 行为均有单测。
