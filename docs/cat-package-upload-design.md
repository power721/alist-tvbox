# 猫源自定义爬虫上传 × 猫影视 node 配置接口 — 设计

日期:2026-09-03(修订:同日按「/open 已废弃」重构、补自定义爬虫合并;2026-09-04 按用户决定**移除整包替换机制与 /open 接口**,仅保留 §0 自定义爬虫合并——§1 之后整包替换相关内容为存档,已不再实现)

> **当前实现状态(2026-09-04)**:
> - `/open`、`/open/{token}` 端点及 SubscriptionService 的 `open()/addCatSites/replaceOpen/mergeOpen/getSites` 链路已删除;TokenFilter 只认 `/node`、`/cat` 前缀。猫影视唯一入口 = `/node/{token}/index.config.js`。
> - 上传通道只支持自定义爬虫:单个 `.js` → `custom/` + 清单登记;zip 仅接受 `custom/`(爬虫+依赖)与 `lib/`(共享依赖,如 cat.js 升级)条目,`custom/spiders.json` 系统维护,三件套/根文件一律拒绝(整包替换已下线)。
> - 备份批次、基线恢复(/cat.zip)、ATV 占位符检测、ConfigFile 行协调随整包机制一并移除。

## 0. 自定义爬虫合并(单爬虫 js 增量并入,内置源零影响)

用户上传的常见形态是**单个爬虫 js**(生态标准 OpenCAT 单文件,混淆与否皆可):要求并入 `index.js` 的站点注册表、同时**保留本站内置源**。机制(2026-09-03 已实现):

1. **上传侧(alist-tvbox)**:非三件套 js(`index.js`/`index.config.js` 之外)落 `custom/{file}` 双位置,并登记清单 `custom/spiders.json`(`[{key,name,type:0,file}]`,key=文件名剥 `_open.js`/`.js`,name 取上传参数);删除联动移除条目;清单文件本身禁改禁删。
2. **bundle 侧(CatVodOpen `src/spider/custom.js`)**:bundle 启动(`start()`)时经 `/node/{token}/custom/spiders.json` 拉清单与爬虫 js、`lib/cat.js`(CAT 库,476KB 内嵌 cheerio);剥 `import 'assets://js/lib/cat.js'`/`export {}` 壳后 `new Function` eval,cat.js 符号按 import 注入,`req`/`localGet`/`localSet` 为宿主全局(cat.js 不提供);`__jsEvalReturn()` 取爬虫对象,按 CAT 契约(`init(cfg)/home(filter)/category(tid,pg,filter,extend)/detail(ids)/play(flag,id,flags)/search(wd,quick,page)`,返回 JSON 字符串)包装成 fastify 爬虫,**追加**进 spiders 注册表——内置源零影响。
3. **分发**:`/node/{token}/**` 深路径端点(TvBoxController)服务子目录文件(custom/、lib/);`node()` 统一走 `isSafePath` 防穿越(任意层级、逐段白名单、拒纯点段)。
4. **相对依赖**:依赖型单文件的 `./lib/xxx.js` 以爬虫所在目录递归拉取(深度≤3),依赖文件经本地包 zip 通道部署(zip 条目 `custom/lib/x.js` 放行,`custom/spiders.json` 拒绝)。
5. **生效**:上传后无需重启——猫影视 App 内刷新配置(触发 bundle 重新 `start()`)即装载新清单;后端不可达/清单缺失/单爬虫失败一律静默跳过。
6. **链路鉴权**:loader 用 `config.atv_pan` 的 api/token 拼 `/node/{token}/...`(现有 TokenFilter 路径 token 放行),零新增鉴权面。

离线验证:`CatVodOpen/test-custom-spider.mjs`(本地 http server 扮演 /node 接口,断言 eval 链/调用契约/坏爬虫容错/后端不可达)。

---

以下为第一轮设计(本地包整包上传),与自定义爬虫机制并存于同一上传通道。

## 1. 需求

用户把猫源 js 文件上传到 alist-tvbox,上传后**自动**通过「猫影视 node 配置接口」生效,无需手工改容器内文件。

**前提(用户已确认):`/open`(config_open.json)生态已废弃。** 猫影视客户端单入口走 `/node/{token}/index.config.js`,与本功能相关的只有 node 三件套;`/open`、`config_open.json`、`*_open.js` 单文件、`my.json` 合并均为存量遗留,本功能**不依赖、不扩展、不删除**(SubscriptionService 相关代码保留现状)。

## 2. 消费模型(已核实)

猫影视客户端宿主(uzn/CatPaw 系播放器)从 node 接口拉「三件套」:

| 文件 | 角色 | 端点行为(SubscriptionService.node:571) |
|---|---|---|
| `index.config.js` | 运行时配置:各爬虫站址(urls)、网盘凭证位、直播源、主题色 | `replaceLegacyConfig` 替换 ATV_*/凭证占位符后 serve |
| `index.js` | esbuild 打包运行时(9.4MB):fastify server + 路由 + **站点注册表**(spider/{book,pan,video}),站点列表由 bundle 决定 | 原样 serve |
| `index.config.js.md5` | 配置变更探测 | 动态返回替换后内容的 md5 |

关键事实:

- **站点列表不在任何配置文件里,在 `index.js` bundle 内**(CatVodOpen/open/nodejs/src/index.js,spider 注册表)。`index.config.js` 只为 bundle 内已注册的爬虫提供 urls/凭证。因此「加站点」= 换 bundle,不存在单站点 js 注入一说(那是废弃的 /open 生态)。
- **三件套是原子替换单元**:Lmentor 这类「猫源本地包」就是同一生态另一方的构建——新爬虫集合 + 新站址配置。上传它 = 站点集合与站址整体焕新。
- **ATV 集成链路**:本项目自建 bundle 的 `index.config.js` 带 `ATV_*` 占位符(atv_media/atv_tgsc/atv_pan 等),由 `replaceLegacyConfig` 在 serve 时注入真实后端地址/凭证。Lmentor 独立构建**没有**这些占位符与对应爬虫——上传替换后,猫影视端的 alist-tvbox 集成源(追剧/TG/盘搜文件夹化)随之消失,换得 Lmentor 的站点集合。这是内容方的取舍,由上传者决定,功能只负责明示风险。
- **持久化覆盖层**(docker/scripts/init-common.sh:30-37):容器初始化在 `/www/cat` 不存在时解压镜像 `/cat.zip`,随后 `cp -r /data/cat/* /www/cat/`。`/data/cat` 是用户覆盖层,**天然抗容器重建/镜像升级**(同名文件覆盖基线)。目前无任何代码写 `/data/cat`,上传功能是第一个写入方。
- **ConfigFile 通道**:`ConfigFileService`(FilesView「配置文件」编辑器,dir 快捷选项已含 `/www/cat`)把 DB 行内容写盘;`setup()/writeFiles()` 与 `syncCat()` 会把所有 `/www/` 前缀 DB 行落盘。**若目标文件已被该编辑器托管(DB 有行),绕过 DB 直写盘会被下次写盘回滚。**
- 鉴权:`TokenFilter`(TokenFilter.java:84)对 `/open`、`/node`、`/cat` 前缀统一要求 basic auth(`/node/{token}/…` 路径 token 等效)。上传文件落在既有受保护命名空间,零新增鉴权面。

## 3. 总体方案:猫源目录即生效(参考 .py 插件目录同步模式)

**采纳 Python 插件「上传到目录自动生效」的产品模式,但目标目录是猫源生效目录 `/www/cat` 本身,不复用 `static/plugins` 目录。**

上传 = 校验后的文件写进 `/data/cat/`(持久)+ `/www/cat/`(即时生效);`/node` 通道零改动、自动 serve 新内容。「自动生效」不需要任何同步层——`node()` 读的就是这个目录,文件落位即生效,比 `.py` 模式(文件 → reconcile → DB 注册 → sites 注入)还少一跳。

### 3.1 对 `.py` 插件目录同步机制(PluginFileSyncService)的参考与不复用

参考的(产品模式):

- **目录即生效**:上传目标就是生效位置,所见即所得;`.py` 是「plugins 文件夹自动同步到订阅源管理」,猫源是「/www/cat 自动通过猫影视node配置接口生效」;
- **上传交互复用**:FilesView 现有 el-upload 心智——拖拽、多文件、zip 自动解压(`autoExtract`)、按目录上传、上传后提示文案风格;
- **文件管理心智复用**:FilesView 静态文件区那套浏览/删除/确认交互,猫源文件区同款。

不复用 `static/plugins` 目录本身,理由:

- **生效位置不同且不可合并**:三件套必须落 `/www/cat`(`node()` 的读取根,且 bundle 内 `./lib/` 相对引用依赖同目录布局);`plugins` 在 `/www/static` 树,复用意味着引入「源目录 → 搬运 → 生效目录」中转层;
- **删除语义有陷阱**:`.py` 模型是「文件删除 = 插件移除」;猫源文件删除需要基线恢复(cat.zip 同名条目)+ 备份回退 + ConfigFile 行协调。中转模型下,用户清理 static 里的「源副本」会触发反向移除误伤生效文件;
- **持久机制不同**:`/www/static` 靠 `VOLUME` 声明;cat 有自己的 `/data/cat` 覆盖层(init 脚本重灌基线后覆盖),中转模型要求容器重建后重新搬运,幂等收敛逻辑复杂且两处可能不一致;
- **生态混淆**:TVBox spider 插件与猫影视 node bundle 混一个目录,管理视角互相污染。

后端唯一有状态的写入是 ConfigFile 协调(§4.3),不新增表、不新增迁移。

## 4. 处理规则

新服务 `CatPackageService` + `CatPackageController`(`/api/cat/*`;与 TvBoxController 现有 `/api/cat/sync` 无路径冲突;`/api/**` 现有 matcher 已覆盖 ADMIN+CLIENT,零安全配置改动)。

### 4.1 输入与校验

- 端点:`POST /api/cat/upload`,multipart `file` + `autoExtract`(对齐 FilesView 现有上传参数心智)。
- 形态判定:单文件直接落盘;zip 在 `autoExtract` 时解包逐条目落盘(Lmentor 样本 = zip,内含 `index.js`、`index.config.js`、两个 md5),不解压则整个 zip 落盘(对该生态无意义,仅存档)。
- 大小上限:单文件 ≤ 32MB(`index.js` 实测 9.4MB,留余量);zip ≤ 64MB。
- 文件名/zip 条目名白名单:相对路径,normalize 后必须落在目标目录内(抄 StaticFileController.upload 的 `normalize()+startsWith()` 模式);文件名字符限 `[A-Za-z0-9_.-]`,允许一级子目录(`lib/` 等),拒绝绝对路径与 `..`。
- 类型白名单:`.js` / `.json` / `.md5`。防任意文件进入 basic-auth 保护的公开目录。
- 内容不解析、不执行,字节流原样落盘。

### 4.2 落盘与备份

每个条目写 `/data/cat/{path}` + `/www/cat/{path}` 双位置:

- 覆盖已存在文件(无论基线版还是此前上传版)前,旧内容存 `/data/cat/backup/{yyyyMMdd-HHmmss}/{path}`;
- 响应返回逐文件结果:新增 / 覆盖 / 备份位置。
- zip 整包作为一个备份批次(同时间戳目录),回退以批次为单位。

### 4.3 ConfigFile 行协调

目标路径已有 `config_file` DB 行(用户曾用 FilesView 编辑器托管)时:

- 文本量小的(`index.config.js` 等):更新该行内容为上传内容,DB 与盘一致;
- 超大文本(`index.js` 9MB 级)不塞 DB:删除该行,响应提示「原在线编辑行已由上传文件接管」。

无行则不建行(上传文件不需要进 DB,`/node` 读的是盘)。

### 4.4 管理与回退

- `GET /api/cat/files`:列 `/data/cat/` 覆盖层内容(文件 + 备份批次),含大小、时间、是否被 ConfigFile 行托管;
- `DELETE /api/cat/files/{path}`:删 `/data/cat` + `/www/cat` 两处;若该文件存在于镜像基线(docker 有 `/cat.zip` 可查条目并恢复该条目到 `/www/cat`;非 docker 跳过恢复),否则彻底移除;关联 ConfigFile 行同步删除;
- `POST /api/cat/backup/{batch}/restore`:整批回退备份(把批次内文件写回双位置,走同样的 ConfigFile 协调);
- 备份目录可整体清空(`DELETE /api/cat/backup`)。

## 5. 前端(web-ui)

**FilesView(文件管理页)加「猫源文件」区块**,与静态文件区并列,复用其交互组件模式(el-upload 拖拽 + 多文件 + zip 自动解压勾选、文件列表、删除确认):

- 上传成功后提示文案对齐 `.py` 的风格:「猫源 js 上传后自动通过猫影视node配置接口生效」;
- 文件列表 = `GET /api/cat/files`(覆盖层文件 + 备份批次两个分组),删除/恢复基线、备份整批恢复均 `ElMessageBox.confirm`;
- 上传结果反馈:覆盖文件与备份位置、**ATV 集成检测**(检测 `/www/cat/index.config.js` 当前内容是否含 `ATV_` 占位符:含 = 保留本站集成源;不含 = 警告「当前包未接入本站后端,追剧/TG 等集成源不可用」);
- 提供可直接复制的 `/node/{token}/index.config.js` 链接(basic-auth 内嵌,同 SubscriptionsView 现有拼法)供验证。

`SubscriptionsView.vue` 猫影视 node 行只加一行提示与跳转入口(「管理猫源文件 →」),不放大 dialog;`.py` 那条提示文案不动。

## 6. 风险与边界

| 风险 | 处置 |
|---|---|
| 替换三件套后 ATV 集成丢失(Lmentor 类独立构建无 ATV_* 占位符/爬虫) | 上传结果页明示检测结论(§5);旧三件套自动备份可整批回退 |
| `config_file` DB 行与直写盘互相覆盖 | 统一协调规则(§4.3) |
| zip 路径穿越 / 任意文件写入 | 条目白名单 + normalize+startsWith 双保险 + 类型白名单 |
| 半包替换(zip 缺 `index.js` 或 `index.config.js`) | 允许(可能是有意只换配置),但响应标注「包内缺 X,沿用现有」避免误解 |
| bundle 与配置版本错配(新 index.js + 旧 index.config.js) | 不做内容级校验(无法可靠判定);备份回退兜底 |
| basic auth 面扩大 | 目录本就受 basic auth 保护;类型白名单只放 js/json/md5 |

明确不做:js 内容解析/校验/合并(外部产物,不理解其内部结构)、远程版本对比与自动更新(本地包无固定分发 URL,用户手动获取;现有 pg/zx/xs 自动任务机制不适用于此)、多用户隔离(cat 为全局资源,ADMIN/CLIENT 即边界)、`node()` 行为任何改动。

**已知现存缺口(不在本功能修,记录备查)**:CatVodOpen 源码注释指出「部分播放器拉三件套但不把 index.config.js 传给 start(),需服务端对 index.js 做占位符注入」,而当前 `node()` 对 `index.js` 原样 serve——本项目自建 bundle 内嵌的占位符在这类宿主下不会被替换。上传功能保持同口径(原样落盘、原样 serve),该缺口如需修复属 `node()` 独立改动。

## 7. 测试要点

`CatPackageService` 单测:

- zip 条目白名单:穿越名/绝对路径/非法后缀拒绝;合法多级 `lib/xx.js` 通过;半包(缺 index.js)通过并在结果标注;
- 双位置落盘与覆盖备份:首次新增、二次覆盖且旧内容进备份批次、备份批次恢复还原;
- ConfigFile 协调:有行且小文本 → 行更新;`index.js` 超大 → 删行;无行 → 不建行;
- 删除:两处删除 + 基线条目恢复(zip stub)+ 关联行删除。

存量回归:`SubscriptionService` 零改动,`node()`/`open()` 存量测试直接复跑。

手工验证(对照 Lmentor 包):上传 zip → `curl -u … /node/{token}/index.config.js` 返回 Lmentor 内容、`/node/{token}/index.config.js.md5` 同步变化;上传本项目自建 bundle(带 ATV_* 占位符)→ serve 内容含真实后端地址;删除上传物或恢复备份 → 回到原三件套;重启容器 → `/data/cat` 覆盖层依旧生效。

## 8. 涉及文件

新增:
- `src/main/java/cn/har01d/alist_tvbox/service/CatPackageService.java`
- `src/main/java/cn/har01d/alist_tvbox/web/CatPackageController.java`
- `src/test/java/cn/har01d/alist_tvbox/service/CatPackageServiceTest.java`

改动:
- `web-ui/src/views/FilesView.vue`(「猫源文件」区块:上传 + 覆盖层/备份管理)
- `web-ui/src/views/SubscriptionsView.vue`(猫影视 node 行加提示与跳转入口)

零改动:`SubscriptionService`(node/open/syncCat)、`MvcConfig`、`TokenFilter`、`WebSecurityConfiguration`;无新表无迁移;无 native-image 资源/反射新增(接口返回 Map/List/String)。
