# 隔离源配套模块

## 用途

部分自定义插件导入同目录配套模块，而不是只依赖 PyPI 包。仅修改爬虫或安装 bs4 无法补齐
这类环境缺口。`build_source_module_context.py` 将管理员明确提供的 Python 模块按原字节
打入离线镜像上下文，不在开发宿主导入或执行模块，也不从导入名自动安装同名第三方包。

```powershell
py -3.14 -X utf8 plugin-maintenance/scripts/build_source_module_context.py --module "D:\inputs\subtitle_sources.py" --module "D:\inputs\cf_challenge_gateway.py" --output "D:\output\source-modules.zip"
```

输出包含 Dockerfile、`modules/*.py` 和逐文件字节数/SHA256 清单。当前支持顶层 `.py`
模块，不包含包目录、二进制扩展或自动依赖解析；模块本身及其传递依赖仍须有可信来源。
输出只接受新 ZIP 路径，拒绝同名模块、路径穿越、非 UTF-8 或语法无效输入。

## 镜像与平台接入

`Dockerfile.source-modules` 接受 `SOURCE_IMAGE`，仅将模块复制到该镜像 Python 的
`purelib` 路径，然后恢复 `65532:65532` 用户。构建不运行模块，不执行 pip 安装。
调用方必须先只读核对基础镜像实际 ID，再使用 `--pull=false --network none` 构建，
并将所得镜像 ID 绑定到指定隔离源通道；上下文清单本身不是实际镜像或运行验收证明。

只替换选定来源的隔离执行通道，保留原件、画像、授权、网络策略、旧通道及 journal。
生产 AT 不因此变更。源的业务请求仍走原受限 HTTP 链路；模块存在不等于授予额外网络、
站点账号或模型权限，也不等于启用第三方网关。

变更后通过维护平台启动受影响来源的 `managed_source` 动态巡检。以实际初始化、HTTP
及后续合同节点判断结果，不用静态装包成功代替联网验收，也不把环境适配称为模型修复。
该镜像仅供隔离 Python 源执行，不是完整维护服务的独立容器交付。

## 配套模块的联网依赖

仅扫描爬虫主文件会漏掉配套模块内的字幕、接口或图片域名。管理员可在
`runner-routes.json` 同目录安装 `source-companions.json`，将明确提供的模块原字节和
实际包含该模块的隔离镜像 ID 绑定。例如：

```json
{
  "schemaVersion": 1,
  "modules": {
    "companion": {
      "path": "companion-modules/companion.py",
      "sha256": "<module SHA256>",
      "imageIds": ["sha256:<accepted image ID>"]
    }
  }
}
```

安装注册表前须在接受的镜像内只读核对模块文件 SHA；注册表本身不是镜像内容证明。
路径可以是绝对路径，也可以相对注册表目录，项目运行副本应保存在项目盘。
注册表不是从爬虫或网页自动接受的内容，不包含 Cookie、模型密钥或账号信息。

`managed_source_setup.py` 按主文件及字面量嵌入模块的显式导入，递进读取注册过的配套模块，
只做 AST 解析，不在宿主导入或执行。未导入模块不读取、不授予域名；循环导入只扫描一次。
建通道从同一运行包中选择包含这些模块的已登记镜像模板，将模块 SHA、镜像 ID 和发现的
域名冻结在通道计划中，再走原有公网 DNS、地址固定和正常 TLS 校验。依赖模块没有直接
域名时，其显式导入的配套模块仍会继续扫描。模块字节或镜像不匹配会指出对应装配错误。

既有通道的网络身份不会因修改注册表而被偷偷扩张。实际补充网络依赖时，显式生成新的
隔离通道身份并切换该来源，保留原通道 journal 和失败记录。模块存在或依赖域名已登记，
都不能代替维护平台上的真实来源巡检与候选复验。

定向检查：

```powershell
Set-Location plugin-maintenance/scripts
py -3.14 -X utf8 -m unittest test_source_module_context
py -3.14 -X utf8 -m unittest test_managed_source_setup
```
