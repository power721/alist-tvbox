# 原始 Python 订阅源插件设计

## 背景

当前订阅源插件将远程 `.txt` 内容保存到 `Plugin.content`，客户端通过 `Atvp.py` 解密并加载。订阅配置通常使用 `csp_PyProxy`，由 `PyProxy.java` 转发到 `Atvp.py`。

需要在不破坏现有加密 `.txt` 插件的前提下，支持原始 `.py` 爬虫入口，并继续使用 `PyProxy.java` 转发。

## 目标与非目标

目标：

- 手工添加和 `spiders_v2.json` 导入均支持 `.py` URL。
- `.py` 内容由后端下载、缓存、刷新，并通过现有 Token 鉴权接口提供给客户端。
- 客户端订阅配置对 `.py` 使用 `csp_PyProxy` 的 `loader` 字段直接加载本地 Python 内容。
- `.txt` 的识别、下载、加密解析、订阅输出和运行模式保持兼容。
- 覆盖后端服务、内容接口和订阅配置生成的自动化测试。

非目标：

- 不新增数据库 `type` 字段或 Flyway 迁移。
- 不修改外部 CatVod 工程或重新构建 `spring.jar`；本变更只使用现有 `PyProxy.java` 已支持的 `loader` 协议。
- 不改变 Python 爬虫自身的运行时 API，也不把 Python 代码在后端执行。

## 方案

### 类型识别

插件类型由 `Plugin.url` 的 URI path 后缀决定：忽略大小写，path 以 `.py` 结尾时视为原始 Python，其他 URL（包括现有 `.txt` 和无扩展名地址）继续按加密文本处理。查询参数和片段不影响判断。该判断集中在 `PluginService` 或共享的包级辅助方法中，避免控制器和订阅服务各自解析。

元数据解析仍从下载内容中读取 `//@id`、`//@version`、`//@name`。没有这些头部时继续使用 URL 文件名作为默认名称；因此普通 Python 源也可以没有 CatVod 元数据头。

### 内容转发

保留现有 `GET /plugins/{token}/{id}.txt`，新增 `GET /plugins/{token}/{id}.py`。两个端点都先调用 `SubscriptionService.checkToken(token)`，再调用 `PluginService.readContent(id)`，避免重复读取逻辑。`.py` 端点使用 `text/x-python; charset=UTF-8`，`.txt` 的响应类型不变。内容为空或插件不存在时保持现有错误语义。

### 订阅配置

`SubscriptionService.buildPluginSite` 根据 URL 类型生成不同 ext：

- 加密 `.txt`：保持现有 `api: "csp_PyProxy"`，ext 使用 `source: /plugins/{token}/{id}.txt`，并按现有设置写入 `local_proxy_config`。
- 原始 `.py`：始终使用 `api: "csp_PyProxy"`，ext 使用 `loader: /plugins/{token}/{id}.py`，同时保留后端 `api`、`token`、`secret` 和可选 `data`（插件扩展配置）。原始 Python 不经过 `Atvp.py` 解密；`PyProxy` 根据 `loader` 直接创建 Python Spider，并把原始 ext 传给它。

原生 Python 运行模式开关只继续影响现有加密 `.txt` 插件的 `Atvp.py` 路径。`.py` 插件按需求固定走 `PyProxy`，这样无论全局模式如何设置，都能使用统一的本地内容转发和播放代理行为。

URL 构造复用当前主机地址、Token 和插件 ID 规则；插件地址本身不下发给客户端，客户端只访问后端缓存接口。

### 生命周期与错误处理

创建、刷新、仓库导入沿用 `PluginService` 的下载和元数据流程。刷新失败时保留之前的 `content`，更新 `lastError` 和 `lastCheckedAt`，与 `.txt` 保持一致。URL 后缀变化时，下一次订阅生成按新类型输出，不需要迁移已有数据。

继续使用现有外部 URL 安全校验和 GitHub 代理 fallback；新增 `.py` 端点不接受路径文件名作为本地路径，只按数据库 ID 查询，避免路径穿越。

## 代码边界

- `src/main/java/cn/har01d/alist_tvbox/service/PluginService.java`
  - 增加可复用的 Python URL 判断。
  - 保持下载、刷新、元数据和内容读取职责不变。
- `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
  - 在 `buildPluginSite` 中按插件类型生成 `.txt` 或 `.py` ext。
- `src/main/java/cn/har01d/alist_tvbox/web/PluginContentController.java`
  - 增加鉴权的 `.py` 内容映射，复用 `readContent`。
- `web-ui/src/views/SubscriptionsView.vue`
  - 将插件地址提示改为同时说明 `.txt/.py`，不新增类型表单或状态字段。
- `src/test/java/cn/har01d/alist_tvbox/service/PluginServiceTest.java`
  - 增加 `.py` 创建、刷新和 URL 类型判断测试。
- `src/test/java/cn/har01d/alist_tvbox/web/PluginContentControllerTest.java`（新建）
  - 覆盖 `.py` 端点 Token 校验、响应媒体类型和内容复用。
- `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
  - 覆盖 `.py` 站点的 `csp_PyProxy`、`loader`、本地 `.py` 地址和 ext 字段，并验证 `.txt` 输出未改变。
- `web-ui/src/views/SubscriptionsView.test.mjs`
  - 覆盖地址提示包含 `.txt` 和 `.py`。

## 数据流

```text
远程 .py URL
    │ PluginService.create/refresh/download
    ▼
Plugin.content（原始 Python 文本）
    │ SubscriptionService.buildPluginSite
    ▼
ext = base64({loader: /plugins/{token}/{id}.py, api, token, secret, data})
    │ 客户端加载 csp_PyProxy
    ▼
PyProxy.java -> GET /plugins/{token}/{id}.py -> 原始 Python Spider
```

`.txt` 仍沿用 `source` 加载和 `Atvp.py` 解密分支。

## 测试策略

先为每个新行为添加失败测试，再实现最小改动：

1. `PluginServiceTest` 验证 `.py` 内容按原文保存、刷新覆盖、没有 Python 头部时按文件名命名，以及带查询参数的 `.py` URL 仍被识别。
2. 内容控制器测试验证合法 Token 返回 Python 文本和正确 Content-Type，非法 Token 不读取插件内容。
3. 订阅服务测试解码 ext，验证 `.py` 使用 `loader` 而不是 `source`，同时验证 `.txt` 仍使用 `source`。
4. 前端静态测试验证输入提示和现有插件添加流程没有破坏。
5. 完成后运行对应 Maven 测试、前端测试和完整 `mvn test`。

## 风险与兼容性

- 依赖 URL 后缀；没有 `.py` 后缀的原始 Python 地址仍会按 `.txt` 处理，这是为保持旧数据兼容而作的明确约束。
- 客户端必须包含支持 `PyProxy.loader` 的 `spring.jar`；当前引用的 `PyProxy.java` 已实现该协议，服务器端不修改 jar。
- Python 爬虫收到的是 PyProxy 的完整 ext 字符串；`data` 继续作为插件自定义配置入口。现有 `.txt` 的 Atvp 解密配置不受影响。
