# YouTube 站点（yt-dlp 运行时）

2026-11 恢复 #760 移除的 YouTube 站点。数据源弃用 Java 库（sealedtx/java-youtube-downloader 单维护者停更 17 个月导致当初移除），改用 yt-dlp 子进程——社区维护、YouTube 改版通常数日内修复、运行时可自更新不需要重发镜像。

## 运行时

- 交付形态：GitHub Releases 的 `yt-dlp.tar.gz` 源码包（纯 Python，Alpine musl 可跑；standalone 二进制是 glibc 专享勿用），解压到 `<data>/yt-dlp/yt-dlp/`，`PYTHONPATH` + `python3 -m yt_dlp` 调起，不依赖 pip。
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
