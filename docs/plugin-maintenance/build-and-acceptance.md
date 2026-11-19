# 构建与验收

## 当前交付

上游贡献只交付 AT 内置模块和 `/#/maintenance` 路由。独立服务与独立前端不在本 PR 中。前后端统一使用[内置构建入口](embedded-integration.md#构建)。

Windows 中文项目目录中，构建前可在当前 PowerShell 加载：

```powershell
. ./plugin-maintenance/scripts/use_project_storage.ps1 -Quiet
```

临时文件、Maven/npm/Python 缓存均放在项目盘，环境仅影响当前进程及子进程，不修改全局配置。

## 定向验证

```powershell
npm.cmd --prefix web-ui run test:maintenance
mvn -Pmaintenance-embedded -Dtest=EmbeddedMaintenanceIntegrationTest test
```

按影响范围选择用例，成批改动后统一验证，不重复未变更来源、模型调用或全量检查。前端构建只产生 AT 的 static；内置 JAR 不包含 maintenance-ui 或独立启动/登录组件。

## NAS

本机负责开发与编译，NAS 接收白名单上下文，负责 Linux 镜像封装及授权范围内的验收。使用已固定摘要的运行基础镜像：

```powershell
py -3.14 -B -X utf8 plugin-maintenance/scripts/build_service_context.py --mode embedded --jar <AT内置JAR> --runtime-image <镜像@sha256:摘要> --output <新上下文.zip>
```

构建上下文包含 JAR、embedded Dockerfile、启动器及 SHA 清单；拒绝旧独立 Web 资源。使用新测试目录，不覆盖生产或现有回滚实例。镜像命名、标签和退役遵守[镜像生命周期](image-lifecycle.md)，不得全局 prune。

同机 T4 用[安装包入口](../../plugin-maintenance/http-acceptance/README.md)和 `compose.t4.yaml` 连接私有 IPC；工作台不挂载 Docker socket。HTTP 验收器、爬虫执行器和完整 AT 宿主镜像是不同产物。

## 结论分层

编译、可信夹具、HTTP 权限、真实来源联网、模型维修、平台发布和客户端播放分别记录，不能互相替代。历史回执只证明其原始源码与部署身份。本次 PR 页面收口不修改 NAS、本机旧服务、生产、模型额度或来源账本。
