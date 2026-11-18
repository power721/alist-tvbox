# 0.77 配置网址参与来源通道准备

## 已交付

此前执行器把 `sourceConfig` 传给 Spider，但自动建通道只解析源码网址。仅在公开配置中提供网址的来源会在初始化前被 `SOURCE_SETUP_ORIGINS_REQUIRED` 阻断。

- `ManagedSourceSetup` 读取当前画像的公开配置，复用 `SourceConfig` 验证及规范摘要；非空配置随请求传给现有基础设施助手。
- 助手复用 Loader 的配置验证及摘要算法，提取配置中的 HTTP/HTTPS origin，接在源码来源后去重。方案身份、远端身份文件和最终回执绑定配置摘要。
- 计划不存完整配置、URL 路径或查询值；源码、配置、配套模块合并后沿用既有网络范围检查。不会执行 Spider 来发现初始来源，也没有放开私网或凭据字段。
- 失败后配置发生变化会生成新的独立准备意图，保留旧失败。已有显式来源通道仍直接复用，不自动扩展或重建。
- 416「豆瓣TMDB追更助手 v96」仍排除所有维护入口；仅519「追剧JS」、520「日历JS」确认资料导航，未改其他用途画像。

## 定向验证

Python 两个受影响模块49项通过，2.067秒。Java两类11项中首轮10项通过，新增中文配置跨语言摘要项失败；原因是测试助手从系统文本流读取UTF-8 JSON，而正式助手从字节流解码。测试改用 `sys.stdin.buffer` 后只复验该项通过，没有重跑已通过的10项。初始失败日志和XML均保留。

所有命令同进程先绝对点载 `plugin-maintenance/scripts/use_project_storage.ps1 -Quiet`。Java使用Android Studio JBR，`MAINTENANCE_PYTHON` 指向项目 `.venv/Scripts/python.exe`，Maven缓存和全部输出位于D盘。

```text
cd plugin-maintenance/scripts
py -3.14 -B -X utf8 -m unittest test_managed_source_setup test_source_origin_discovery
mvn.cmd -o -f plugin-maintenance/server/pom.xml -Dmaven.repo.local=<workspace>/.codex/cache/m2 "-Dtest=ManagedSourceSetupTest,SourceConfigTest" test
mvn.cmd -o -f plugin-maintenance/server/pom.xml -Dmaven.repo.local=<workspace>/.codex/cache/m2 "-Dtest=ManagedSourceSetupTest#publicConfigurationAndCanonicalHashReachTheHelperWithoutExecutingTheSource" test
mvn.cmd -o -f plugin-maintenance/server/pom.xml -Dmaven.repo.local=<workspace>/.codex/cache/m2 -DskipTests package
```

Java命令在工作区根执行。一次打包成功，5.719秒；无Web、全量、内置或镜像构建。新旧JAR只变化 `ManagedSourceSetup.class`、共享来源解析脚本及三项版本信息，文件集合和其他字节相同。

## 本机交付

一次换版后18990运行 `0.77-SNAPSHOT`，Java28220 / launcher35340。

- JAR SHA256：`a925d3b3f9068d78f2dfdaa2f5e258006aab8e6c9853ef13730392c6752f099d`。
- managed bundle保持 `de158cff0465e1a79277a874002c123ec618b3e939bcfdf57b69f3823c8d43a8`。
- 54来源、45既有通道ready；来源、模型配置、预算、调度API指纹及连接/通道/镜像能力/配套模块文件保持。
- 首页和两静态资源实际HTTP字节与新JAR匹配。0.76及0.75独立JAR、0.75内置JAR和冻结M5包保持，停止旧JVM后保存本次数据库副本。
- 新源执行、模型调用、生产写入、NAS修改和新镜像均为0。本次没有重新运行旧来源、0.76原件比对或已关闭C6验收。

权威回执为外层 `recovery/configured-origins-077-20261116/final-summary.json`；操作助手 `recovery/rollout_configured_origins_077_20261116.py` 的prepare/switch/observe均已终态，禁止重放。

## 保留边界

定向检查证明配置网址可进入冻结计划、中文配置摘要跨语言一致、错误配置及错误回执不能安装通道，不证明来源联网健康。只读查看当前未建通道的7个来源配置，均无配置网址；未为验收改写来源或制造真实成功。

配置来源的真实隔离联网验收、远端决定的初始网址、IPv6和原M2其余媒体矩阵仍未关闭。0.77内置构建/运行及新M5包未生成；0.75已验双入口和冻结包不因此重开。长期观察、整机/生产恢复、获准正式PR及后置容器分支按原范围保留，M1-M5目标仍active。
