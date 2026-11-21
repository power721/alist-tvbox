# YouTube 站点（yt-dlp 运行时）

2026-11 恢复 #760 移除的 YouTube 站点。数据源弃用 Java 库（sealedtx/java-youtube-downloader 单维护者停更 17 个月导致当初移除），改用 yt-dlp 子进程——社区维护、YouTube 改版通常数日内修复、运行时可自更新不需要重发镜像。

## 运行时

- 交付形态：GitHub Releases 的 `yt-dlp.tar.gz` 源码包（纯 Python，Alpine musl 可跑；standalone 二进制是 glibc 专享勿用），解压到 `<data>/yt-dlp/yt-dlp/`，`PYTHONPATH` + `python3 -m yt_dlp` 调起，不依赖 pip。
- **Python 版本门控：yt-dlp 要求 3.11+**。探测按序尝试 `-Dyoutube.python`、`YOUTUBE_PYTHON`、`python3`、`python3.13/3.12/3.11`、`/usr/bin/python3`、`/usr/local/bin/python3`，逐个做版本检查（老 Alpine 基座/Ubuntu 22.04 的系统 python3 是 3.10 会被跳过）。全部过旧时站点隐藏，配置页「YouTube 运行时」卡片给出原因（可 `-Dyoutube.python=/path/to/python3.11+` 指定）。
- 首次启动后台自动下载；每日 06:47 比对 GitHub 最新 release 自动更新（`app.youtube-dlp-auto-update=false` 关闭，`app.youtube-dlp-url` 可换下载源）。
- 出站代理沿用 `<data>/proxy.txt`（GitHub 下载与 YouTube 请求都走它）。
- 未装 python3（JVM jar 裸机）时站点不可用，返回 502——与 T4 插件运行时同一降级模式。

## 端点（契约与移除前一致）

- `GET /youtube[/{token}]`：`ids=recommend|<id>` 详情；`wd=` 搜索；`t=` 分类浏览（`channel@UC..`/`@handle`/`playlist@PL..` 前缀或关键词）。
- `GET /watch[/{token}]?id=`：播放。`X-CLIENT` 头决定线路：
  - FongMi 等支持 DASH 的客户端 → 代理官方 HLS master 清单（自带全清晰度+音轨自适应；不再手搓 MPD，Format 无 initRange/indexRange 已不可行）；
  - `node` → URL 列表（≥1080p 纯视频）+ `extra.audio` 音轨表；
  - 其余 → muxed itag18 直链代理；
  - 直播 → 一律 master HLS 清单。
- `GET /youtube-proxy[/{token}]?u=<base64url>`：中继。m3u8 内容做嵌套 URL 重写（master→variant→segment 全部代理化）；其余 Range 透传流式转发。上游白名单 `*.googlevideo.com`/`manifest.googlevideo.com`/`youtube.com`，防 SSRF。

## 订阅内置源（csp_YouTube）

- 订阅源管理内置「YouTube」源，由 yt-dlp 运行时可用性门控（未装 python3/未完成下载时不进列表与配置）。
- T3 形态：type=3 站点，jar=spring.jar（内含 `com.github.catvod.spider.YouTube` 薄管道 spider，同 BiliBili 模式，X-CLIENT 透传包名供服务端识别 EXO 下发 HLS master）。
- T4 形态：type=4，api=`/t4/{token}/v/YouTube`，五动作（home/category/detail/search/play）由 T4Controller 直投 YoutubeController；播放地址本就是服务端中继，T4 壳子直连。
- 改 spider 后重打 jar：CatVodTVSpider `./build.sh`（自动回拷 spring.jar/spring.md5，SPIDER_JAR_VERSION 随 md5 自动变化）。

## 运行时管理（网页）

- `GET /api/youtube/ytdlp[?refresh=1]`：当前/最新版本（latest 缓存 5 分钟防 GitHub API 限流）。
- `POST /api/youtube/ytdlp/update`：手动更新（强制重下，与自动更新共用闸门）。
- 两者随 `/api/**` 规则要求 ADMIN/CLIENT。配置页「豆瓣电影数据」卡片下方有「YouTube 运行时」卡片展示版本并提供按钮。

## GitHub 代理（复用全局 `GitHubProxyService`）

yt-dlp 的 GitHub 下载与版本探测接入项目统一的 GitHub 代理机制（设置页配置，`/data/github_proxy.txt`，最多 5 个按优先级）：

- 下载（首装/手动/自动更新）：按代理列表逐个拼前缀尝试（FileDownloader 同惯例），末尾直连兜底；`latest/download` 动态资产被代理拦截时自动按已知 tag 重试静态资产 `releases/download/{tag}/yt-dlp.tar.gz`；响应按 gzip 魔数校验，反爬验证页会被拦下换下一个候选。
- 版本探测：`api.github.com` 直连（或 proxy.txt）优先，失败后依次走代理的 releases/latest 页面解析 tag（302 落地 URL 或页面内链接）。
- 显式配置 `app.youtube-dlp-url`（非默认值）时下载直接用之、代理不介入（版本探测仍可用代理）。
- 实测（2026-11）：`gh-proxy.com`/`ghproxy.net` 可用（含 latest/download 动态资产）；`ghproxy.cn` 对非浏览器请求全反爬。

## 搜索筛选 sp 编码

YouTube `sp` 参数为 protobuf base64url，本仓构造逻辑经官方筛选面板抓取+真机实证对齐（**网上流传的标签表时间位是错位的，勿参考**）：

| 维度 | 编码 |
|---|---|
| 排序（外层 field1） | rating=1, upload_date=2, view_count=3 |
| 时间（Filters field1） | hour=1, today=2, week=3, month=4, year=5 |
| 类型（Filters field2） | video=1, channel=2, playlist=3, movie=4 |
| 特征 | HD=field4:1, 4K=field14:1, HDR=field25:1 |

权威校验值：`CAM`(播放量)、`EgIIAw`(本周)、`EgIQAg`(频道)、`EgJwAQ`(4K)、`EgPIAQE`(HDR)——见 `YoutubeServiceTest`。

## 已知事实（排障）

- yt-dlp 分片 URL 路径**中段**含 `/playlist/index.m3u8` 再接 `/file/seg.ts`——manifest 判定必须按后缀 `endsWith(".m3u8")`，contains 会把 TS 分片误判成清单。
- 宿主 python 若装过 pip 版 yt-dlp 会遮蔽下载副本，子进程必须显式注入 `PYTHONPATH`。
- exec 子进程的 stdout 必须由独立线程排空后再 waitFor 超时，否则缓冲填满互相死锁、超时永不触发。
- tar.gz 是仓库快照：顶层 `yt-dlp/` 内还有一层 `yt-dlp/`（包目录），解压路径 `yt-dlp/yt-dlp/yt_dlp`。
- H2 `setting` 表列名小写且 `svalue` 为保留字形态，SQL 访问须带引号小写。
