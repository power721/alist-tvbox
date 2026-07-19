# 原始 Python 爬虫 backend_parse 兼容设计

## 背景与根因

原始 Python 插件目前通过 `csp_PyProxy` 将 `loader` 指向插件自己的 `.py` 文件。这样 `PyProxy` 会直接实例化原始 Spider，绕过 `src/main/resources/static/Atvp.py`。

`Atvp.py` 并不是单纯的加密文件加载器，它还实现了 `backend_parse=True` 的运行时协议：

- 首页、首页视频、分类和搜索结果改写为可展开的目录项；
- 详情结果缓存网盘信息和播放上下文；
- 对原始网盘链接调用后端 `/parse/{token}`；
- 对 `1@...` 播放标识调用后端 `/play/{token}`；
- 将目录项、详情和播放上下文传给过滤器。

因此，原始 Python 插件即使设置 `self.backend_parse = True`，当前也不会得到这些行为。

## 目标

- 原始 `.py` 插件也通过 `Atvp.py` 运行时包装。
- `backend_parse=True` 的原始 Python 爬虫复用现有目录、详情、解析和播放逻辑。
- `.txt` 加密插件的协议和行为完全不变。
- 不修改外部 CatVod 工程、`PyProxy.java` 或重新构建 `spring.jar`。
- 原始 Python 插件的 `data`、Token 和本地代理配置按 Atvp 既有规则传给内层 Spider。

## 非目标

- 不在 Java `PyProxy` 中复制 Python 运行时逻辑。
- 不改变后端 `/parse/{token}` 和 `/play/{token}` 的 API 结构。
- 不改变插件 URL 类型识别、缓存、刷新和鉴权规则。

## 设计

### 订阅 ext 协议

原始 `.py` 站点仍使用 `api: "csp_PyProxy"`，但 ext 改为：

```json
{
  "loader": "ATV_ADDRESS/Atvp.py",
  "api": "ATV_ADDRESS",
  "source": "ATV_ADDRESS/plugins/TOKEN/ID.py",
  "raw": true,
  "token": "TOKEN",
  "secret": "SECRET",
  "data": "PLUGIN_EXTEND",
  "local_proxy_config": {}
}
```

`loader` 选择 Atvp 包装器，`source` 保持原始 Python 内容接口。`raw: true` 是显式协议标志，只对原始 Python 生效；它避免通过文件后缀猜测是否跳过解密。

加密 `.txt` 继续使用当前 `loader`/`source` 组合和 Atvp 加密解包路径，不增加 `raw` 字段。

### Atvp.py 原始源分支

`Spider.init` 读取 ext 后，仍通过 `_load_source(source)` 获取文本。随后按 payload 的 `raw` 布尔值选择：

```python
package_text = self._load_source(source)
source_text = package_text if self._is_raw_source(payload) else self._decrypt_secspider_source(package_text)
```

新增 `_is_raw_source(payload)` 只接受字典中的真值 `raw`，非字典或缺失字段均返回 `False`。除源文本取得方式外，内层 Spider 的加载、`_compose_inner_extend`、backend API、Token、过滤器和所有 `backend_parse` 分支不变。

### 数据流

```text
Plugin.content (.py)
    -> GET /plugins/{token}/{id}.py
    -> PyProxy(loader=Atvp.py)
    -> Atvp._load_source(source)
    -> raw=true: 跳过 secspider 解密
    -> inner Spider.init(data + token + proxy)
    -> backend_parse=True 的 Atvp 目录/详情/parse/play 协议
```

### 错误处理与兼容性

- `raw` 缺失或为假时仍严格执行现有 secspider 解密，防止误把加密文本当作 Python 执行。
- 原始源下载失败、内容为空、Token 错误继续使用现有后端错误处理。
- Atvp 原始源执行失败时沿用 PyProxy 的初始化错误行为，不向后端引入新的错误格式。
- 现有 `.txt` ext 快照和 Java 代理/原生 Python 运行模式测试必须继续通过。

## 代码边界

- `src/main/resources/static/Atvp.py`
  - 增加 raw payload 判断和跳过解密分支。
- `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
  - 原始 Python ext 将 `loader` 改为 `/Atvp.py`，增加 `source` 和 `raw: true`。
- `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceTest.java`
  - 验证原始 Python 的 loader/source/raw 字段和 `.txt` 回归。
- `src/test/python/test_Atvp_raw_backend_parse.py`
  - 验证 raw 源跳过解密，并验证 `backend_parse=True` 的 Atvp 分类/解析播放路径仍可执行。

## 测试策略

1. 先新增 Java ext 测试，确认原始 Python 输出 `loader=/Atvp.py`、`source=.py`、`raw=true`，当前实现应失败。
2. 新增 Python Atvp 单元测试，构造 raw ext 和最小内层 Spider；让 `_decrypt_secspider_source` 在被调用时抛错，证明 raw 分支必须跳过它。
3. 在同一 Python 测试中让内层 Spider 设置 `backend_parse=True`，验证分类结果被改写为 `Atvp.DETAIL_PREFIX`，并模拟 `/parse`/`/play` 响应验证后端端点调用。
4. 实现最小修改后运行 Java 聚焦测试、Python 单元测试、完整 `mvn test`、前端测试和构建。

