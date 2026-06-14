# 站点高级设置（更多设置）设计

## 概述

在订阅定制可视化编辑器的站点表格中，为每个站点行添加"更多设置"按钮，弹窗允许配置 searchable、quickSearch、filterable、changeable、ext、style、order 及高级设置（timeout/indexs/playUrl/click/categories/header）。

## UI 变更

### 站点表格操作列

现有操作列（width: 120）改为 width: 180，每行新增"更多设置"链接按钮：

- 上游源行：`更多设置` + `恢复默认`
- 自定义站点行：`更多设置` + `删除`
- 内置源/插件源行：`更多设置`

### 新增 "站点设置" Dialog

独立 dialog，标题"站点设置"，宽度 640px，append-to-body destroy-on-close。

表单字段（label-width: 120）：

| 字段 | 控件 | 说明 |
|---|---|---|
| ext 扩展 | textarea rows=3 | 爬虫扩展数据 |
| searchable 搜索 | select (0/1/2) | 不可搜索/可搜索/聚合搜索 |
| 快速搜索 | switch (1/0) | |
| 筛选 filterable | switch (1/0) | |
| 线路切换 | switch (1/0) | |
| 卡片风格 style | select + input | type(rect/oval/list) + ratio |
| 排序 order | input | 数字 |
| --- 分隔线: 高级设置 --- | | |
| 超时 timeout | input-number (min=0) | |
| 索引模式 indexs | switch (1/0) | |
| 播放前缀 playUrl | input | |
| 点击拦截 click | input | |
| 分类白名单 | select multiple | |
| 请求头 header | key-value table | name + value + 删除按钮 |

不显示：key、type、api、jar。

## 数据流

所有行的"更多设置"行为一致，包括上游源、自定义站点、内置源、插件源。

### 打开弹窗 (`openSiteAdvanced`)

从 siteRows 中对应行读取现有值填充 reactive `siteAdvancedForm`：
- `ext`, `searchable`, `quickSearch`, `filterable`, `changeable`
- `styleType`/`styleRatio`（从 `row.style` 解构）
- `order`, `timeout`, `indexs`, `playUrl`, `click`
- `categories`（数组），`headerPairs`（key-value 数组）

默认值：searchable=1, quickSearch=1, filterable=1, changeable=0, 其余为空。

### 确认保存 (`confirmSiteAdvanced`)

将 form 值写回 siteRows 对应行。标记该行有高级设置覆盖（新增 `row.hasAdvancedOverride = true`）。

### 序列化变更

`subscriptionConfig.mjs` 的 `serialize` 函数中，**所有非自定义站点行**（upstream/builtin/plugin）的 override 对象从仅写 `name`+`order` 扩展为写入全部已配置字段：

```js
// 非自定义站点行序列化
if (!row.isCustom) {
  const o = { key: row.key }
  if (row.name && row.name !== row.originalName) o.name = row.name
  if (row.order != null && row.order !== '') o.order = Number(row.order)
  if (row.hasAdvancedOverride) {
    // 写入 searchable/quickSearch/filterable/changeable/ext/style/timeout/indexs/playUrl/click/categories/header
  }
  if (Object.keys(o).length > 1) sites.push(o)
}
```

原来的 `row.origin === 'upstream'` 判断改为 `!row.isCustom`，这样内置源和插件源的覆盖也会写入 `config.sites`。

## 改动文件

| 文件 | 改动范围 |
|---|---|
| `web-ui/src/components/SubscriptionConfigEditor.vue` | 操作列加按钮 + 新 dialog + openSiteAdvanced/confirmSiteAdvanced |
| `web-ui/src/utils/subscriptionConfig.mjs` | serialize 中上游源行增加高级字段写回 |

## 不涉及的文件

- 后端无变更（override JSON 由前端组装，后端原样存储）
- 现有"添加自定义站点" dialog 不变
