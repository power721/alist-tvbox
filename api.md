# AList-TvBox API 文档

本文档列出了 AList-TvBox 项目中的所有 REST API 端点。

---

## 目录

- [核心 API](#核心-api)
- [用户与认证](#用户与认证)
- [站点管理](#站点管理)
- [播放相关](#播放相关)
- [媒体集成](#媒体集成)
- [网盘账户](#网盘账户)
- [订阅与配置](#订阅与配置)
- [系统管理](#系统管理)

---

## 核心 API

### TvBoxController
主要的 TvBox 客户端 API 端点

| 方法 | 路径 | 描述                          |
|------|------|-----------------------------|
| GET | `/vod1` | TvBox API v1（无token）        |
| GET | `/vod1/{token}` | TvBox API v1（带token）        |
| GET | `/vod` | TvBox API（无token）           |
| GET | `/vod/{token}` | TvBox API（带token）- 分类/搜索/详情 |
| GET | `/m3u8` | M3U8播放列表（无token）            |
| GET | `/m3u8/{token}` | M3U8播放列表（带token）            |
| GET | `/api/qr-code` | 获取二维码                       |
| GET | `/tv/device` | 获取设备信息                      |
| POST | `/tv/action` | 设备操作（同步等）                   |
| GET | `/api/devices` | 列出所有设备                      |
| POST | `/api/devices` | 添加设备                        |
| POST | `/api/devices/-/scan` | 扫描设备                        |
| POST | `/devices/{token}/{id}/sync` | 同步设备                        |
| POST | `/api/devices/{id}/push` | 推送到设备                       |
| DELETE | `/api/devices/{id}` | 删除设备                        |
| GET | `/api/profiles` | 获取配置文件列表                    |
| GET | `/api/token` | 获取令牌                        |
| POST | `/api/token` | 更新令牌                        |
| GET | `/sub/{id}` | 获取订阅配置（无token）              |
| GET | `/sub/{token}/{id}` | 获取订阅配置（带token）              |
| GET | `/repo/{id}` | 获取多仓订阅配置（无token）            |
| GET | `/repo/{token}/{id}` | 获取多仓订阅配置（带token）              |

---

## 用户与认证

### UserController
用户账户管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/users` | 列出所有用户 |
| POST | `/api/users` | 创建用户 |
| POST | `/api/users/{id}` | 更新用户 |
| DELETE | `/api/users/{id}` | 删除用户 |
| POST | `/api/accounts/login` | 用户登录 |
| POST | `/api/accounts/logout` | 用户登出 |
| GET | `/api/accounts/principal` | 获取当前用户信息 |
| POST | `/api/accounts/update` | 更新账户 |

### TenantController
租户管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET/POST | `/api/tenants` | 列出租户 |
| POST | `/api/tenants` | 创建租户 |
| POST | `/api/tenants/{id}` | 更新租户 |
| DELETE | `/api/tenants/{id}` | 删除租户 |

---

## 站点管理

### SiteController
存储站点管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/sites` | 列出所有站点 |
| POST | `/api/sites` | 创建站点 |
| GET | `/api/sites/{id}` | 获取站点详情 |
| GET | `/api/sites/{id}/browse` | 浏览站点文件 |
| GET | `/api/sites/{id}/index` | 列出索引文件 |
| POST | `/api/sites/{id}` | 更新站点 |
| POST | `/api/sites/{id}/updateIndexFile` | 更新索引文件 |
| DELETE | `/api/sites/{id}` | 删除站点 |

### ShareController
分享链接管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/shares` | 分页列出分享链接 |
| POST | `/api/shares` | 创建分享 |
| POST | `/api/share-link` | 添加分享链接 |
| POST | `/api/shares/{id}` | 更新分享 |
| DELETE | `/api/shares/{id}` | 删除分享 |
| DELETE | `/api/shares` | 批量删除分享（按类型） |
| POST | `/api/delete-shares` | 批量删除分享 |
| GET | `/quark/cookie/{id}` | 获取夸克Cookie |
| GET | `/uc/cookie/{id}` | 获取UC Cookie |
| GET | `/115/cookie/{id}` | 获取115 Cookie |
| GET | `/baidu/cookie/{id}` | 获取百度Cookie |
| GET | `/api/storages` | 列出存储 |
| POST | `/api/storages` | 验证存储 |
| DELETE | `/api/storages` | 清理存储 |
| POST | `/api/storages/{id}` | 重新加载存储 |
| POST | `/api/import-shares` | 导入分享 |
| POST | `/api/import-share-file` | 导入分享文件 |
| GET | `/api/export-shares` | 导出分享 |
| POST | `/api/open-token-url` | 更新Open Token URL |

---

## 播放相关

### PlayController
视频播放控制

| 方法 | 路径 | 描述 |
|------|------|------|
| REQUEST | `/p/{token}/{id}` | 代理视频流 |
| GET | `/play-urls` | 列出播放URL（分页） |
| DELETE | `/play-urls` | 删除所有播放URL |
| GET | `/play` | 获取播放URL（无token） |
| GET | `/play/{token}` | 获取播放URL（带token） |

### MyPlayController
夸克网盘播放

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/play/quark` | 解析夸克播放 |
| GET | `/api/proxy/quark/{id}` | 代理夸克视频流 |

### VideoController
视频操作

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/videos/{id}/rate` | 评分视频 |
| GET | `/api/videos/{id}/rate` | 获取视频评分 |
| POST | `/api/videos/{id}/rename` | 重命名视频 |
| POST | `/api/videos/{id}/move` | 移动视频 |
| DELETE | `/api/videos/{id}` | 删除视频 |

### SubtitleController
字幕服务

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/subtitles` | 获取字幕内容 |

---

## 媒体集成

### BiliBiliController
哔哩哔哩集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/bili/cookie/{id}` | 获取B站Cookie |
| GET | `/bilibili` | B站API（无token） |
| GET | `/bilibili/{token}` | B站API（带token）- 分类/搜索/详情 |
| GET | `/api/bilibili/status` | 获取登录状态 |
| GET | `/api/bilibili/check` | 检查登录 |
| POST | `/api/bilibili/checkin` | 签到 |
| POST | `/api/bilibili/cookie` | 更新Cookie |
| POST | `/api/bilibili/login` | 扫码登录 |

### EmbyController
Emby媒体服务器集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/emby` | 列出Emby服务器 |
| POST | `/api/emby` | 创建Emby配置 |
| GET | `/api/emby/{id}` | 获取Emby配置 |
| POST | `/api/emby/{id}` | 更新Emby配置 |
| DELETE | `/api/emby/{id}` | 删除Emby配置 |
| GET | `/emby` | Emby API（无token）- 浏览 |
| GET | `/emby/{token}` | Emby API（带token）- 浏览 |
| GET | `/emby-play` | Emby播放（无token） |
| GET | `/emby-play/{token}` | Emby播放（带token） |

### JellyfinController
Jellyfin媒体服务器集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/jellyfin` | 列出Jellyfin服务器 |
| POST | `/api/jellyfin` | 创建Jellyfin配置 |
| GET | `/api/jellyfin/{id}` | 获取Jellyfin配置 |
| POST | `/api/jellyfin/{id}` | 更新Jellyfin配置 |
| DELETE | `/api/jellyfin/{id}` | 删除Jellyfin配置 |
| GET | `/jellyfin` | Jellyfin API（无token）- 浏览 |
| GET | `/jellyfin/{token}` | Jellyfin API（带token）- 浏览 |
| GET | `/jellyfin-play` | Jellyfin播放（无token） |
| GET | `/jellyfin-play/{token}` | Jellyfin播放（带token） |

### DoubanController
豆瓣集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/meta` | 列出元数据（分页） |
| POST | `/api/meta` | 添加元数据 |
| POST | `/api/meta/{id}` | 更新元数据 |
| POST | `/api/fix-meta` | 修复唯一元数据 |
| POST | `/api/meta-scrape` | 抓取元数据 |
| POST | `/api/meta/{id}/scrape` | 抓取单个元数据 |
| DELETE | `/api/meta/{id}` | 删除元数据 |
| POST | `/api/meta-batch-delete` | 批量删除元数据 |
| GET | `/api/versions` | 获取远程版本 |

### TmdbController
TMDB集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tmdb/meta` | 列出TMDB元数据 |
| POST | `/api/tmdb/meta` | 添加TMDB元数据 |
| POST | `/api/tmdb/meta/{id}` | 更新TMDB元数据 |
| POST | `/api/tmdb/meta-scrape` | 抓取TMDB元数据 |
| POST | `/api/tmdb/meta-sync` | 同步TMDB元数据 |
| POST | `/api/tmdb/meta/{id}/scrape` | 抓取单个TMDB元数据 |
| POST | `/api/tmdb/meta/-/scrape` | 抓取TMDB元数据（请求体） |
| DELETE | `/api/tmdb/meta/{id}` | 删除TMDB元数据 |
| POST | `/api/tmdb/meta-batch-delete` | 批量删除TMDB元数据 |

### TelegramController
Telegram集成

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/telegram/reset` | 重置Telegram |
| POST | `/api/telegram/login` | 登录Telegram |
| POST | `/api/telegram/logout` | 登出Telegram |
| GET | `/api/telegram/search` | 搜索消息 |
| GET | `/tg-search` | Telegram搜索API（无token） |
| GET | `/tg-search/{token}` | Telegram搜索API（带token） |
| GET | `/tg-db` | Telegram豆瓣API（无token） |
| GET | `/tg-db/{token}` | Telegram豆瓣API（带token） |
| GET | `/tgsz` | 搜索ZX格式 |
| GET | `/tgs` | 搜索PG格式（GET） |
| POST | `/tgs` | 搜索PG格式（POST） |
| GET | `/tgs/s/{id}` | 搜索Web（GET） |
| POST | `/tgs/s/{id}` | 搜索Web（POST） |
| GET | `/api/telegram/user` | 获取Telegram用户 |
| GET | `/api/telegram/chats` | 获取所有聊天 |
| GET | `/api/telegram/channels` | 列出频道 |
| POST | `/api/telegram/resolveUsername` | 解析用户名 |
| POST | `/api/telegram/channels` | 保存频道 |
| PUT | `/api/telegram/channels` | 批量更新频道 |
| DELETE | `/api/telegram/channels/{id}` | 删除频道 |
| POST | `/api/telegram/reloadChannels` | 重新加载频道 |
| POST | `/api/telegram/validateChannels` | 验证频道 |
| GET | `/api/telegram/history` | 获取聊天历史 |

### LiveController
直播集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/live` | 直播API（无token） |
| GET | `/live/{token}` | 直播API（带token） |
| GET | `/live-play` | 直播播放（无token） |
| GET | `/live-play/{token}` | 直播播放（带token） |

### HuyaController
虎牙直播集成

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/huya` | 虎牙API（无token） |
| GET | `/huya/{token}` | 虎牙API（带token） |

---

## 网盘账户

### AccountController
阿里云盘账户

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/ali/accounts` | 列出阿里账户 |
| POST | `/api/ali/accounts` | 创建阿里账户 |
| POST | `/api/ali/accounts/{id}/checkin` | 签到 |
| GET | `/api/ali/accounts/{id}/checkin` | 获取签到日志 |
| POST | `/api/ali/accounts/{id}/token` | 更新令牌 |
| POST | `/api/ali/accounts/{id}` | 更新账户 |
| DELETE | `/api/ali/accounts/{id}` | 删除账户 |
| GET | `/ali/token/{id}` | 获取阿里令牌 |
| GET | `/ali/open/{id}` | 获取阿里Open令牌 |
| POST | `/api/alist/login` | 更新AList登录 |
| GET | `/api/alist/login` | 获取AList登录信息 |
| POST | `/api/alist/password` | 重置密码 |
| POST | `/api/schedule` | 更新计划时间 |
| POST | `/ali/auth/qr` | 获取二维码 |
| GET | `/ali/auth/qr` | 检查二维码状态 |
| POST | `/ali/auth/token` | 获取令牌 |
| POST | `/ali/access_token` | 刷新令牌 |

### PanAccountController
网盘账户管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/pan/accounts` | 列出网盘账户 |
| POST | `/api/pan/accounts` | 创建网盘账户 |
| POST | `/api/pan/accounts/{id}` | 更新账户 |
| POST | `/api/pan/accounts/{id}/token` | 更新令牌 |
| DELETE | `/api/pan/accounts/{id}` | 删除账户 |
| POST | `/api/pan/accounts/-/qr` | 获取二维码 |
| POST | `/api/pan/accounts/-/token` | 获取刷新令牌 |
| POST | `/api/pan/accounts/-/info` | 获取账户信息 |

### PikPakController
PikPak网盘

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/pikpak/accounts` | 列出PikPak账户 |
| POST | `/api/pikpak/accounts` | 创建PikPak账户 |
| POST | `/api/pikpak/accounts/{id}` | 更新PikPak账户 |
| DELETE | `/api/pikpak/accounts/{id}` | 删除PikPak账户 |

### Pan115Controller
115网盘

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/pan115/token` | 获取115令牌 |
| GET | `/api/pan115/status` | 获取115状态 |
| GET | `/api/pan115/result` | 获取115结果 |

---

## 订阅与配置

### SubscriptionController
订阅配置管理

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/subscriptions` | 保存订阅 |
| GET | `/api/subscriptions` | 列出所有订阅 |
| DELETE | `/api/subscriptions/{id}` | 删除订阅 |

### IndexController
索引管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/index/version` | 获取远程版本 |
| POST | `/api/index` | 创建索引 |

### IndexFileController
索引文件管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/index-files` | 获取索引内容（分页） |
| GET | `/api/index-files/settings` | 获取搜索设置 |
| POST | `/api/index-files/settings` | 设置搜索设置 |
| DELETE | `/api/index-files` | 删除索引文件 |
| POST | `/api/index-files/exclude` | 切换排除项 |
| POST | `/api/index-files/upload` | 上传索引文件 |
| GET | `/api/index-files/download` | 下载索引文件 |
| POST | `/api/index-files/validate` | 验证索引文件 |

### IndexTemplateController
索引模板管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/index-templates` | 列出索引模板（分页） |
| POST | `/api/index-templates` | 创建索引模板 |
| GET | `/api/index-templates/{id}` | 获取索引模板 |
| POST | `/api/index-templates/{id}` | 更新索引模板 |
| DELETE | `/api/index-templates/{id}` | 删除索引模板 |

### ConfigFileController
配置文件管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/files` | 列出配置文件 |
| POST | `/api/files` | 创建配置文件 |
| GET | `/api/files/{id}` | 获取配置文件 |
| POST | `/api/files/{id}` | 更新配置文件 |
| DELETE | `/api/files/{id}` | 删除配置文件 |

### SettingController
系统设置管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/settings` | 获取所有设置 |
| GET | `/api/settings/{name}` | 获取设置 |
| POST | `/api/settings/apikey` | 生成API密钥 |
| POST | `/api/settings` | 更新设置 |
| GET | `/api/settings/export` | 导出数据库 |

### NavigationController
导航管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/nav` | 列出导航 |
| PUT | `/api/nav` | 批量保存导航 |
| POST | `/api/nav` | 创建导航 |
| POST | `/api/nav/{id}` | 更新导航 |
| DELETE | `/api/nav/{id}` | 删除导航 |

### AListController
AList服务管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/alist/status` | 检查AList状态 |
| GET | `/api/alist/start/status` | 获取启动状态 |
| POST | `/api/alist/status` | 更新状态 |
| POST | `/api/alist/stop` | 停止AList |
| POST | `/api/alist/start` | 启动AList |
| POST | `/api/alist/restart` | 重启AList |
| GET | `/api/alist/port` | 获取端口 |
| POST | `/api/alist/reset_token` | 重置令牌 |

### AListAliasController
AList别名管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/alist/alias` | 列出别名 |
| POST | `/api/alist/alias` | 创建别名 |
| GET | `/api/alist/alias/{id}` | 获取别名 |
| POST | `/api/alist/alias/{id}` | 更新别名 |
| DELETE | `/api/alist/alias/{id}` | 删除别名 |

### ParseController
解析服务

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/parse` | 解析URL |

### RemoteSearchController
远程搜索

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/pansou` | 获取盘搜信息 |
| GET | `/pansou` | 盘搜API（无token） |
| GET | `/pansou/{token}` | 盘搜API（带token） |
| GET | `/tgsp` | 搜索PG（GET） |
| POST | `/tgsp` | 搜索PG（POST） |
| POST | `/tgsp/s/{id}` | 搜索PG频道 |

### ZxConfigController
ZX配置

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/zx/version` | 获取ZX版本 |
| GET | `/zx/config` | 获取ZX配置 |

### PgTokenController
PG令牌管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/pg/version` | 获取PG版本 |
| GET | `/pg/lib/tokenm` | 获取PG令牌 |

---

## 系统管理

### HistoryController
历史记录管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/history` | 列出历史记录（分页） |
| POST | `/api/history` | 创建历史记录 |
| GET | `/api/history/{id}` | 获取历史记录 |
| POST | `/api/history/{id}` | 更新历史记录 |
| POST | `/api/history/-/delete` | 批量删除历史记录 |
| DELETE | `/api/history/{id}` | 删除历史记录 |
| GET | `/history/{token}` | 拉取历史（带token） |
| POST | `/history/{token}` | 推送历史（带token） |
| DELETE | `/history/{token}` | 删除历史（带token） |

### TaskController
任务管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/tasks` | 列出任务（分页） |
| DELETE | `/api/tasks` | 清理任务 |
| GET | `/api/tasks/{id}` | 获取任务 |
| DELETE | `/api/tasks/{id}` | 删除任务 |
| POST | `/api/tasks/{id}/cancel` | 取消任务 |

### LogController
日志管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/logs` | 获取日志（分页） |
| GET | `/api/logs/download` | 下载日志 |

### SystemController
系统信息

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/system` | 获取系统信息 |

### ImageController
图片代理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/images` | 获取图片（URL参数） |
| GET | `/images/{id}` | 获取图片（ID） |

---

## 统计信息

- **总Controller数量**: 40
- **总API端点数量**: 约 300+

---

## 备注

1. 大部分 TvBox 客户端 API 支持无 token 和带 token 两种访问方式
2. `/vod`、`/bilibili`、`/emby`、`/jellyfin`、`/live`、`/huya` 等接口支持统一的分类/搜索/详情查询模式
3. 带有 `{token}` 路径变量的端点会进行订阅令牌验证
4. 分页接口支持 `Pageable` 参数（page, size, sort）
5. 文件上传接口使用 `MultipartFile` 参数
