# AT 源主动维护平台

通过真实联网巡检发现 Python 爬虫故障，由模型生成候选，平台隔离复验，成功后进入持久发布队列。支持独立 Web 服务和可选的 AT 内置页面，默认增量发布，替换发布须显式选择。

- [独立服务安装](../docs/plugin-maintenance/standalone-release.md)
- [AT 内置集成](../docs/plugin-maintenance/embedded-integration.md)
- [本次上游审阅分层与验收边界](../docs/plugin-maintenance/github-review-2026-11-19.md)

当前为 RC 开发候选，不是通用零干预稳定版。不要将示例配置、空模型连接或静态检查通过当作真实来源修复成功。站点源码、Cookie、模型密钥、活动数据库和运行回执不随公开源码交付。
