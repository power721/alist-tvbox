# 产品 HTTP 验收入口

此模块从当前 AT 源码构建独立验收 JAR，使用实际控制器、鉴权、H2 和
Servlet 请求上下文，通过既有 Unix broker 调用隔离 Spider。运行时不依赖
JUnit、Mockito、旧实验测试配置或历史构建回执，不在 Java 宿主执行源代码。

## 构建

在仓库根目录使用 Java 21：

```powershell
mvn -Pmaintenance-http-acceptance -DskipTests package
```

输出 `plugin-maintenance/http-acceptance/target/at-maintenance-http.jar`，
不会替换独立维护服务或 AT 内置包。受影响检查入口：

```powershell
mvn -Pmaintenance-http-acceptance "-Dtest=HttpAcceptanceDriverTest,T4ControllerTest" test
```

测试使用自编写的 broker 对端，不执行 Spider、不访问真实站点，不能计入源验收。

## NAS 封装

`scripts/build_managed_http_context.py` 接收当前 JAR、JAR SHA-256、
NAS 基础镜像引用与实际 image ID，输出白名单 ZIP 及逐文件摘要清单。
使用其 `--help` 查看参数；它不连接 NAS、不构建镜像、不执行源。
封装前须在 NAS 只读核对基础镜像 ID，再以禁止拉取、禁止构建联网的方式构建。
基础镜像需提供 `/jre/bin/java` 和现有系统运行环境。

镜像保留既有 main class、classpath、非 root 用户、固定 broker 挂载及回执协议。
输入从标准输入读取，完整源正文只进入既有隔离执行链。
当前必需矩阵包括两种代理模式下的 GET、表单和 JSON 共 36 动作；
源返回代理地址时，再走同会话的实际 AT 回调，不以另行直抓替代。

镜像上下文、JAR 构建、本机 HTTP 夹具、NAS 镜像存在及当前源业务验收是不同层级。
新 JAR/镜像不能沿用历史包的 R04/R05；按当前任务与 journal 重新建立证据。
历史 `at-t4-lab` 仅保留固定基线实验，不是当前产品镜像的构建入口。
