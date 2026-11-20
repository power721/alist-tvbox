# AT 内置源维护

通过真实联网巡检发现 Python 爬虫故障，由模型生成候选，平台隔离复验，成功后进入持久发布队列。默认增量发布，替换发布须显式选择。

本上游贡献只交付 AT 内置模块：AT 管理员登录后，从“源维护”进入 `/#/maintenance`。工作台直接使用 AT 的 Vue、Element Plus、登录和导航，不使用 iframe，也不提供独立管理页面、独立端口、独立账号或独立工作台镜像。

- [内置构建与数据库说明](../docs/plugin-maintenance/embedded-integration.md)
- [使用入口](../docs/plugin-maintenance/getting-started.md)
- [能力与结构](../docs/plugin-maintenance/module-overview.md)
- [上游合入步骤](../docs/plugin-maintenance/merge-guide.md)

`plugin-maintenance/server` 保存共享控制面源码和独立的测试夹具；不是可启动的发行服务。真正的运行入口是 AT 的 `AListApplication`，仅显式 `maintenance-embedded` profile 加载维护模块。

不承诺任意站点零干预修复。静态检查或 HTTP 200 不等于真实修复成功。站点源码、Cookie、模型密钥、活动数据库和私有运行回执不随源码交付。
