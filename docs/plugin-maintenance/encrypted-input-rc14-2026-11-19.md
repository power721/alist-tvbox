# RC14 加密插件输入与原件恢复

## 交付范围

2026-11-19，补通 AT 外置静态/公开 HTTPS 读取、内置宿主缓存读取与管理页的 `.txt/secspider` 导入。保持原有大小、路径、凭据隔离和严格签名校验，包 SHA 与明文 SHA 分开记录，预览确认仍检查来源漂移。

新增管理员下载接口 `GET /api/plugin-maintenance/sources/{id}/plugin-inputs/{inputId}/original`，检查原件哈希、长度和来源归属，禁止缓存。界面入口为输入身份抽屉中的“下载导入原件”。返回导入原件，不会把之后的修复内容塞回旧签名包。

## 真实样本

生产 AT 仅 GET 读取元数据、现有内容授权和已缓存包，不刷新或修改插件。首次本机直读公开包连接重置，之后从生产缓存取样；未将 AT 凭据转发到第三方。私有原包、明文及封装实验不进入 Git。

| 生产 ID | 样本 | 原包/明文字节 | 原包 SHA 前缀 | 明文 SHA 前缀 |
| --- | --- | --- | --- | --- |
| 226 | 短剧网[短] | 13984 / 10090 | 8250af2e2c9d | 9403e413b782 |
| 229 | 磁力熊[磁] | 28167 / 20727 | fe9145874ec7 | e864bfcbf43e |
| 239 | 麦田影院 | 12513 / 8989 | 0bc14f874d2a | 835149365b47 |

三个原包均通过固定 ATVP 的 Ed25519 验签、AES-GCM 解密、声明哈希与语法校验。私有副本尾部追加无业务作用的注释仅作封装实验，不称为修复：新随机载荷 nonce 的 AES-GCM 往返 3/3 通过，但沿用原签名的修改包 3/3 被拒绝。平台 HTTP 同样返回 `PLUGIN_SIGNATURE_REJECTED`，不创建错误来源；平台原件下载 3/3 与取样包逐字节一致。

结论：能重新加密，不等于能重新生成原 AT 信任的签名。当前未提供原签名私钥或原作者签名服务，不能交付带修改内容的可信原格式加密包；未跳过验签、替换信任公钥或冒充原作者签名。原包恢复与修改后明文维护是不同路径。

## 平台联网巡检

三个包通过平台本地加密输入入口导入，再使用现有 `managed_source` 隔离运行器联网执行，不在宿主直接执行插件。此次真实来源执行验证本地包导入；AT 列表加密导入的内外置适配由定向测试验证，不冒称三次真实生产列表导入。

| 样本 | 受管来源 ID | 巡检 ID | 必需通过 | 首要断点 |
| --- | --- | --- | --- | --- |
| 短剧网 | 9e4c0c3d-e845-44d2-92c3-401408a1bf4d | 9fe4544f-9aad-4586-aa9f-36ce6e80f109 | 5/9 | 分类 `EGRESS_HTTP_FAILED`，无完整 HTTP 响应 |
| 磁力熊 | 810f54cb-c9f2-40b6-af4c-6344df3309cf | 60ab3cf2-a92f-4288-b465-9cafd1623c13 | 6/9 | 首页/分类三次 200，详情 404，C07 列表为空；不是搜索失败 |
| 麦田影院 | 6b7b0c96-11b4-4504-b212-6bc8c56f521a | 474f38fa-5c0d-4f06-8f55-4c954ea75042 | 8/9 | R01 `PLAYER_URL_INVALID` |

三次执行终态且清理确认、容器不存在。不得把 `COMPLETED` 当成业务成功，三个来源均不具备健康/发布资格。没有新模型维修或生产发布，封装实验不是自动修复证据。磁力站继续按磁力离线合同维护，不要求直接媒体链接；详情 404 与离线下载能力分开归因。

## 验证与部署

- 服务端 `AtReadClientTest,AtHostAccessTest,AtPluginInputIntegrationTest,PluginInputIntegrationTest`：48 项通过。补下载 HTTP 的未登录拒绝/管理员字节一致断言后仅复验该用例 1 次通过。
- 内置 `EmbeddedAtHostAccessTest`：1 项通过。`node --test tests/maintenance/atPluginInput.test.mjs tests/maintenance/pluginInput.test.mjs`：11 项通过。
- `npm run build` 的类型/Vite 与内置 `mvn -o -Pmaintenance-embedded -Dtest=EmbeddedAtHostAccessTest package` 成功。未全量测试；运行器和清单未变，未重复构建。
- NAS18992 已 RC14，镜像 `sha256:b10e9992cc491db74be2a6114d2630003e2dded7a40aec52c6ae5d28d0652452`，JAR `b2135ebc7fae4541a8e3c626265ba48ba175012b879bafcc69edbc85764d3a30`，规范标签 `at-plugin-maintenance-embedded:v0.106.0-rc14-20261119t044826015989z-7792127e353f`。
- 原库冷复制保留 4 旧来源、旧维修及模型账本，新增 3，共 7。11Web文件与实际HTTP逐字节一致；仍仅 AT `/#/maintenance`，独立旧路径404。
- 生产身份、18991、18990、8797保持。自动巡检/模型递交/生产发布仍关；旧爱看4/4未追加。精确退役更旧RC12容器和镜像各1，保留RC13回滚与数据，不prune。

私有回执在工作区外层 `recovery/encrypted_samples_rc14_20261119/`，`delivery.json` 是正式覆盖统计；早期 `probe-summary.json` 的嵌套字段选择曾输出 null，不作业务结论。助手各阶段全终态，不可重放。

## 下一步

加密交付需对接受权签名器，不用自建密钥伪装原 AT 兼容。业务先按现有基线推进磁力熊详情404、短剧网网络和麦田取链协议；不重复取样、巡检或在相同证据上盲调模型。稳定发行、未知故障无人值守修复成功至自动发布仍未验收。
