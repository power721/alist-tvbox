# Release Notes - 1.134.0

## 新增
- 恢复 YouTube 站点并使用 yt-dlp 运行时获取数据，支持配置页查看当前/最新版本及手动更新。
- YouTube 直播按分类路由，并在直播栏目按真实直播状态过滤。
- YouTube 播放支持外挂字幕轨；订阅内置源支持 T3 和 T4 直投。
- T4 配置支持纳入推送站，服务端可解析推送 URL。
- Dockerfile 支持通过 `BASE_TAG` 切换基础镜像。

## 优化与修复
- yt-dlp 下载支持 GitHub 前缀镜像，改进 Python 命令探测和 Python 3.11+ 版本校验。
- Docker 守卫校验 H2 引擎版本，并锁定 H2 为 2.3.232。
