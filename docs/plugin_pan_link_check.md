# 网盘链接有效性检测 API（插件用）

供 spider / filter 插件在 TVBox 客户端运行时回调后端，检测网盘分享链接是否有效。

## 为什么需要单独的端点

`POST /api/pansou/check/links` 属 `/api/**`，需要 `X-API-KEY`（CLIENT/ADMIN）。插件运行在客户端，只持有**订阅 vod token**，没有 api_key，无法调用。因此提供 token 网关端点：

```
POST /check-links/{token}
POST /check-links            # 无 token 变体，等价于 token=""
```

鉴权仅靠订阅 token（`SubscriptionService.checkToken`），与 `/parse/{token}`、`/pansou/{token}` 同一机制。

## 请求

```json
{
  "items": [
    { "url": "https://www.alipan.com/s/xxxx", "disk_type": "aliyun" },
    { "url": "https://pan.quark.cn/s/yyyy" }
  ]
}
```

- `items[].url`：网盘分享链接（必填）。
- `items[].disk_type`：**可选**。省略时后端按 URL host 自动推断。显式取值见下表。

| disk_type | 网盘 |
|-----------|------|
| `aliyun` | 阿里云盘 |
| `baidu` | 百度网盘 |
| `quark` | 夸克网盘 |
| `uc` | UC 网盘 |
| `115` | 115 网盘 |
| `123` | 123 云盘 |
| `tianyi` | 天翼云盘（189） |
| `mobile` | 移动云盘（139） |
| `xunlei` | 迅雷云盘 |
| `pikpak` | PikPak |
| `guangya` | 光雅网盘 |
| `magnet` / `ed2k` | 磁力 / 电驴 |

## 响应

```json
{
  "results": [
    { "url": "https://www.alipan.com/s/xxxx", "state": "ok",       "summary": "链接有效" },
    { "url": "https://pan.quark.cn/s/yyyy",   "state": "bad",       "summary": "链接失效" }
  ]
}
```

| state | summary | 含义 |
|-------|---------|------|
| `ok` | 链接有效 | 可访问 |
| `bad` | 链接失效 | 失效/取消/不存在 |
| `locked` | 链接受限 | 需提取码或受限 |
| `uncertain` | 状态不确定 | 后端未能判定 |

> 需在后端配置任一盘检地址：`panCheckUrl` / `tgSearch` / `panSouUrl`（应用设置）。三者按该优先级委托；都未配置时落到 PanSou 默认地址。

## 在 filter 插件中调用

filter 插件的 context 含 `api`（后端基址）与 `token`（vod token），直接用 `requests` 调用即可：

```python
import requests

def filter_detail(context, detail):
    api = context["api"]
    token = context["token"]
    # 收集要校验的分享链接
    items = [{"url": vod.get("vod_play_url")} for vod in detail.get("list", [])]
    if not items:
        return detail
    rsp = requests.post(
        f"{api}/check-links/{token}",
        json={"items": items},
        timeout=15,
    )
    if rsp.status_code != 200:
        return detail
    bad = {r["url"] for r in rsp.json().get("results", []) if r.get("state") == "bad"}
    detail["list"] = [v for v in detail["list"] if v.get("vod_play_url") not in bad]
    return detail
```
