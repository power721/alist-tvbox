# 容器托管 secspider 插件编译流程

本文档记录自用三方插件编译的新路径：容器内自动生成 Ed25519 密钥对和 master secret，编译时自动读取，编译成功后自动写入静态插件仓库并导入插件管理。

## 目标

1. 用户不需要手工维护私钥、公钥、master secret。
2. 密钥文件保存在 Docker 数据卷中，镜像升级不丢失。
3. `Atvp.py` 运行时从容器配置读取 self keyring，不需要每次改源码回填。
4. 三方插件编译成功后自动进入本地静态插件仓库。
5. 编译成功后自动导入插件管理，订阅生成时可直接使用。

## 持久化路径

密钥材料保存到：

```text
/data/secspider/
```

具体文件：

```text
/data/secspider/self-ed25519-private.pem
/data/secspider/self-ed25519-public.pem
/data/secspider/self-master-secret.txt
/data/secspider/atvp-keyring.json
```

`Atvp.py` 默认读取：

```text
/data/secspider/atvp-keyring.json
```

也可以通过环境变量覆盖：

```text
ATV_SECSPIDER_KEYRING=/path/to/atvp-keyring.json
```

只要 Docker 运行时保留：

```text
-v /你的目录/alist-tvbox:/data
```

这些密钥就会随 `/data` 卷保留，升级镜像不会丢失。

## 静态插件仓库

编译后的插件包自动保存到：

```text
/www/static/self-plugins/
```

典型结构：

```text
/www/static/self-plugins/
  spiders_v2.json
  py/
    javbus_self.txt
```

外部访问地址：

```text
http://服务器IP:4567/static/self-plugins/spiders_v2.json
http://服务器IP:4567/static/self-plugins/py/javbus_self.txt
```

当前 Docker 运行命令需要保留：

```text
-v /你的目录/alist-tvbox/www-static:/www/static
```

这样静态插件仓库也会在升级镜像后保留。

## WebUI 使用方式

路径：

```text
订阅管理 -> 订阅源管理 -> 三方插件编译
```

默认开关：

```text
托管密钥: 使用容器密钥
自动导入: 编译后导入插件管理
```

普通使用只需要填写：

```text
插件名称
插件版本
插件 ID
kid
remark
插件明文
```

不需要填写：

```text
Ed25519 私钥
Ed25519 公钥
master secret
```

后端会从 `/data/secspider` 自动读取。

## 生成和重置密钥

容器首次启动时会自动生成密钥。

WebUI 也提供两个按钮：

```text
生成密钥对
重置密钥对
```

说明：

1. `生成密钥对`：如果已有密钥，不覆盖；如果缺失，生成。
2. `重置密钥对`：强制生成新密钥。
3. 重置后，旧密钥编译出的自有插件无法再被新的 self keyring 解密，需要重新编译。
4. 原版 stock 插件不受影响。

## 播放链路

编译并导入后，插件管理里会出现新的插件记录。

生成订阅时，插件仍然走项目原有路径：

```text
csp_PyProxy + spring.jar + base64(ext)
```

`ext` 内会指向服务端：

```text
loader: ATV_ADDRESS/Atvp/{token}/{keyringVersion}.py
source: ATV_ADDRESS/plugins/{token}/{pluginId}.txt
```

`Atvp.py` 解密时会尝试：

```text
stock keyring
self keyring
```

self keyring 来源优先级：

1. `ATV_SECSPIDER_KEYRING` 环境变量。
2. `/data/secspider/atvp-keyring.json`。
3. `/opt/atv/data/secspider/atvp-keyring.json`。
4. 源码内 `_self_public_key_chunks` / `_self_master_secret_chunks`。

当前推荐使用第 2 种：容器 `/data/secspider/atvp-keyring.json`。

### 默认订阅联调修正

2026-07-17 联调默认订阅路径时发现：`csp_PyProxy` 获取到的 `Atvp.py` 是在 FongMi/TV 客户端内执行，不能直接读取服务端容器里的 `/data/secspider/atvp-keyring.json`。如果只把 keyring 保存到容器本地，但下发的 `Atvp.py` 仍是静态文件，客户端会在 `//@sig` 校验阶段报：

```text
PyProxy init failed: ValueError: The signature is not authentic
Atvp spider is not initialized
```

因此当前实现会在服务端动态渲染 token 化 loader：

```text
/Atvp/{token}/{keyringVersion}.py
```

该文件会把容器托管的 `_self_public_key_chunks` 和 `_self_master_secret_chunks` 注入到下发给客户端的 `Atvp.py` 中。`keyringVersion` 由 chunks 计算，重置密钥后 URL 会变化，避免 FongMi 继续使用旧缓存。

排查时要区分三类失败：

1. 订阅未切换：日志仍指向旧域名或旧 token。
2. 包体损坏：服务端本地用当前 keyring 验签/解密失败。
3. loader keyring 不匹配：服务端本地验签/解密成功，但客户端报 `The signature is not authentic`。

第 3 类就是本次默认路径的问题，修复点在 loader 下发方式，不在插件包本身。

## 回退方法

如果新密钥或新插件不可用：

1. 在插件管理里禁用或删除刚导入的插件。
2. 如果只是插件源码问题，保留密钥，重新编译插件。
3. 如果误重置密钥，只能用新密钥重新编译自有插件。
4. Docker 镜像可回退到上一个 `sha-*` 标签，但 `/data/secspider` 中的密钥文件不会因为镜像回退自动变化。

## 安全边界

1. 私钥保存在 `/data/secspider/self-ed25519-private.pem`，不进入 Git。
2. WebUI 不回显私钥和 master secret。
3. `atvp-keyring.json` 中的 master secret 是混淆 chunks，不是强加密。它的目标是让播放器运行时能解包，而不是抵抗拿到容器文件的人。
4. 不绕过 Ed25519 签名校验。
5. 不绕过 SHA256 明文 hash 校验。
6. 自用插件仓库如果要保密，应保持服务器、镜像、静态目录和插件管理权限私有。

## 验证命令

后端目标测试：

```powershell
.\mvnw.cmd "-Dtest=PluginCompilerServiceTest,PluginCompilerAtvpInteropTest,PluginControllerCompileTest,PluginControllerSecurityTest,SecspiderKeyServiceTest,SelfPluginFileServiceTest" -DfailIfNoTests=false -DforkCount=0 test
```

前端类型检查：

```powershell
cd web-ui
.\node_modules\.bin\vue-tsc.cmd --noEmit --pretty false
```

前端构建：

```powershell
cd web-ui
.\node_modules\.bin\vite.cmd build
```
