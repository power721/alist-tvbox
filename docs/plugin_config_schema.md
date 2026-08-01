# Spider 插件配置结构声明 (PLUGIN_CONFIG_SCHEMA)

## 背景

Spider 插件（`Plugin`）的扩展配置 `extend` 是一段 JSON，最终被 base64 编码进订阅的 `ext` 字段，由 TVBox 客户端传给爬虫的 `init(self, extend)`。

过去用户只能在「扩展配置」对话框里手写 JSON，不知道字段名和格式。现在插件可以在脚本里**自声明配置结构**，前端自动渲染可视化表单（与过滤器 `FILTER_CONFIG_SCHEMA` 同一套机制）。

## 声明方式

在 `.py` 脚本顶层定义常量 `PLUGIN_CONFIG_SCHEMA`，值为一个 JSON 对象：

```python
PLUGIN_CONFIG_SCHEMA = {
  "description": "观影配置",
  "allowAdditional": false,
  "fields": [
    {"key": "sites", "label": "站点", "type": "string", "required": true, "aliases": ["site", "host"], "placeholder": "https://..."},
    {"key": "username", "label": "用户名", "type": "string"},
    {"key": "password", "label": "密码", "type": "secret"},
    {"key": "cookie", "label": "Cookie", "type": "secret", "placeholder": "可代替账号密码"}
  ]
}

class Spider(BaseSpider):
    def init(self, extend=""):
        cfg = json.loads(extend) if extend else {}
        self.host = cfg.get("sites") or cfg.get("site") or cfg.get("host") or ""
        self.username = cfg.get("username", "")
        self.password = cfg.get("password", "")
        self.cookie = cfg.get("cookie", "")
        ...
```

> 常量必须放在脚本顶层（不能在类/函数内部）。常量名严格为 `PLUGIN_CONFIG_SCHEMA`。

### 顶层字段

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `description` | string | `""` | 展示在配置弹窗头部 |
| `allowAdditional` | boolean | `true` | 是否允许填写声明之外的额外字段 |
| `singleValueKey` | string | `""` | 当历史配置是单个字符串时，映射到该字段 |
| `example` | object | — | 示例 JSON（预留） |
| `fields` | array | `[]` | 字段定义列表 |

### field 字段

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `key` | string | 必填 | 写入 extend JSON 的键名 |
| `label` | string | `""` | 中文展示名 |
| `type` | string | `string` | `string` / `number` / `boolean` / `object` / `secret` |
| `required` | boolean | `false` | 是否必填（保存前校验） |
| `description` | string | `""` | 字段说明 |
| `defaultValue` | any | — | 默认值，仅用于展示，不强制写回 |
| `placeholder` | string | `""` | 输入框占位提示 |
| `aliases` | string[] | `[]` | 兼容旧配置的别名（如 snake_case / camelCase） |
| `children` | field[] | `[]` | `type=object` 时的嵌套字段 |

### type 取值

- `string` — 普通文本
- `number` — 数字
- `boolean` — 开关
- `object` — 嵌套对象；配 `children` 渲染子字段，否则按 JSON 文本框编辑
- `secret` — 脱敏输入（密码 / Cookie 等敏感字段，前端带显示/隐藏切换），写入 extend 时仍是普通字符串

`aliases` 用于平滑迁移：用户旧配置里写了 `site`，新 schema 的 `key` 是 `sites`，把 `site`、`host` 列入 `aliases` 后，表单会自动回填旧值，保存时统一归一到 `key`。

## 兼容：注释式声明

注释式声明仍被识别（`//` 与 `@config-schema` 之间可有可无空格，冒号可选）：

```
// @config-schema {"description":"...", "fields":[{"key":"site","type":"string"}]}
```

## 加密 `.txt`（secspider）如何生效

线上分发的爬虫通常是 secspider 加密包：`.py` 源码（含 `PLUGIN_CONFIG_SCHEMA`）被 AES 加密进 `payload.base64:`，**alist-tvbox 不解密**，因此常量式声明读不到。

解决办法：打包时（atv-spiders `build_secspider_package`）自动把 `PLUGIN_CONFIG_SCHEMA` 抽出、压成单行 JSON，以**明文头** `//@config-schema:{...}` 写进 `.txt`。该头不进签名区（只影响 UI），alist-tvbox 的注释式解析器会直接识别。即：

- `.py` 直接导入 / 明文 `.txt` → 读 `PLUGIN_CONFIG_SCHEMA` 常量
- secspider 加密 `.txt` → 读 `//@config-schema:{...}` 明文头（打包时自动生成）

两路殊途同归，无需 alist-tvbox 持有解密密钥。

## 后端接口

- `GET /api/plugins/{id}/config-schema` — 返回该插件的解析结果（`Plugin.configSchema` 为运行时 transient 字段，不入库）。
- 解析逻辑见 `util/ConfigSchemaParser`，过滤器 (`FILTER_CONFIG_SCHEMA`) 与 spider 插件 (`PLUGIN_CONFIG_SCHEMA`) 共用。
- 订阅输出路径不变：`extend` → `data` → base64 `ext`。

## 前端

声明了字段的插件，在「订阅源 → 配置」时弹出「表单编辑 / JSON 编辑」双 Tab（复用 `PluginFilterConfigFieldEditor.vue`）；未声明则回退为纯文本框。过滤器配置 UI 行为不变。
