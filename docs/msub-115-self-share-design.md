# 追剧 115 资源自有化(转存 + 自建永久分享)设计

状态:已拍板(2026-09-12),实施中。拍板结论:①115driver(用户自有库)沉淀 API 封装;②**自动删源释放空间**——转存的目的就是拿分享快照,快照到手盘内文件即冗余;③分批快照形态;④首批接管主源、后续批次走补缺挂载;⑤旧批次分享永不删。

## 1. 需求与价值

追剧资源当前直接引用上游 115 分享,上游删除/失效即断流,需要高频巡检兜底。参照「115 快照式分享」的稳定玩法:

1. 把上游分享**转存到自己的 115 盘**(已有能力:`/api/fs/share/save` 服务端秒传);
2. 对自有盘目录**创建自己的永久分享链接**,每批内容固化一个快照;
3. **建分享并验证后立即删除盘内源文件**——115 分享是快照语义,删源不影响已建分享,盘空间随即释放(转存空间占用是瞬时的);
4. 上游失效不再影响已入账集数;自有批次快照不可变,挂上后无需重列检测;
5. 约束:115 分享有审核时间(创建后立即消费可能延迟,靠重试兜底);往分享加文件必须新建分享 → **每追到新内容 = 新建一个批次分享**(恰好匹配快照语义);
6. 开关默认关——单批转存需要瞬时容纳整批文件(一季可达百余 GB),空间大的用户才打开。

## 2. 115 永久分享机制(抓包研究结论)

用户抓包(2026-09-12,web cookie 会话)证实的两步创建流程:

### 2.1 创建分享 `POST https://webapi.115.com/share/send`

表单:`user_id`(cookie `UID=` 第一段数字)+ `file_ids`(目标**目录**的 115 cid)+ `is_asc=0` + `order=file_name` + `ignore_warn`。

- `ignore_warn=0`:预检,只返回 `share_title` / `total_size` / `folder_count`,不产出分享码;
- `ignore_warn=1`:实际创建,返回 `receive_code`(自动 4 位提取码)、`share_code`、`share_url`(`115cdn.com/s/{code}`)、`share_command`(`/{share_code}-{receive_code}/`)、`share_ex_duration: "15天"`(**默认只有 15 天**)、`share_duration_options`(`-1` = 长期)。

### 2.2 改永久 `POST https://webapi.115.com/share/updateshare`

表单:`share_code` + `share_duration=-1`。Referer 用 `https://cdnres.115.com/`(与 share/send 的 `115.com` 不同,照抄)。

**永久分享 = share/send(ignore_warn=1) → updateshare(-1),两步缺一不可。**

### 2.3 快照与审核语义

- **快照不可变**:分享内容 = 创建时刻目录快照(`share/snap` 即快照接口)。建后删盘内源文件不影响分享 → **建分享成功的瞬间即可删源**;加文件只能新建分享。
- **审核**:分享有 `share_state/forbid_reason` 状态域,创建后立即消费可能延迟 → 建分享后须挂载列目录验证快照完整,再删源;验证失败不删源,下轮重试。
- **鉴权**:纯 web cookie + UA + Referer 裸 POST,无签名;仅 cookie 版账号可用(开放平台无分享 API)。

## 3. 实现架构(拍板后)

```
alist-tvbox (Java 编排)
  └─ POST /api/fs/share/create {path}        ← 新端点,admin token
       PowerList drivers/115/share.go(内联 resty,照 115_share saveTo 先例)
         ├─ POST webapi.115.com/share/send (ignore_warn=1, user_id=client.UserID)
         └─ POST webapi.115.com/share/updateshare (share_duration=-1)
115driver(用户自有库)pkg/driver/share.go 同步沉淀正式封装
  (PowerList 因 docker 构建内 go mod download 不能挂本地 replace,内联实现不被发版时序阻塞;
   115driver 发版后 PowerList 可切换正式封装)
```

- PowerList 端点收 `path`(账号挂载下的目录路径),内部 `op.Get` 解析 cid——标准 `/api/fs/list`、`/api/fs/get` 的 `ObjResp` 不透出对象 id,Java 拿不到 cid,必须驱动侧做;
- 删源复用现有 `/api/fs/remove`(115 驱动 `Remove` → `client.Delete` → `webapi.115.com/rb/delete` 彻底删除);回收站兜底清理(需账号 `delete_code`,PowerList `CleanRecycleBin(password, rid)` 按条目清理非全清)已有先例,一期不额外做;
- 转存复用现有 `/api/fs/share/save`(`serverSavable` 8→8 同型,上游须为 115 分享)。

## 4. 核心流程(分批快照)

目录布局:`{115账号挂载根}/{msub_transfer_root 我的追剧}/{剧名 季 元数据标签}/`——与 TRANSFER 同规格共享;**目录复用,每批文件平铺进去,建分享后全数删除**(快照=当批文件集,share_title=剧目录名,美观)。

每批(巡检尾部触发 / 手动按钮):

1. **收集批次目标集**:当前可看(LIVE)但集源行不属于任何 `self115` 资源行的集,且其来源资源是 115 分享(type 8,serverSavable 同型);非 115 来源(夸克/UC/磁力产物)一期不自有化;
2. **增量转存**:`shareSave(src_dir=来源资源挂载路径, names=本批文件名, dst_dir=剧目录)`,每批 ≤10 个对象分批;
3. **残留校验**:建分享前列剧目录,内容须恰为本批文件(上轮删源失败的残留先删光,防混入快照);
4. **建永久分享**:`POST /api/fs/share/create` → `{share_code, receive_code, share_url}`(内部两步已串好);
5. **验证**:临时挂载新分享列目录,文件数/集数与本批一致(防审核延迟/假成功);
6. **删源**:`/api/fs/remove` 删除剧目录内本批全部文件(验证通过才删;删除失败下轮由步骤 3 兜底);
7. **入账**:资源行 `link=https://115.com/s/{code}?password={rc}`、`type=8`、`source="self115"`、`score=1000`、state=MOUNTED——**首批** `activate()` 接管主源固定路径 `mount_path`(播放历史不断链),上游分享回落 CANDIDATE 继续作增量来源;**后续批次**走 `mountAux` 补缺挂载;
8. **事件** `TYPE_SELF_SHARE`(批次集数范围 + 累计自有集数)+ push。

限频:每订阅每日建分享次数上限(`app.subscription.self-share-daily-limit`,默认 3),以当日事件表计数,防 115 风控。

## 5. 巡检语义(「不用经常检测」的落点)

- `source=self115` 资源行(主源+补缺)**豁免死链链路**:`onInvalid` 不 retire/不 `markDeadLink`(自有分享几乎不死,列目录失败大概率账号会话/限流问题),记事件+下轮重试,连续多轮失败才告警;
- `refreshAuxMounts` 跳过 self 行(快照不可变,重列白耗 share/snap 配额);
- 新集检测照旧:`computeMissing` → `fillGaps`(探测上游候选/搜索补池)→ 下批自有化;
- 优化点(二期):主源为 self 时 `syncInventory` 可跳过重列(快照不变则 inventory 不变)。

## 6. 配置与 UI

- 订阅级列 `self_share BOOLEAN DEFAULT FALSE`(迁移 V53)+ Request/Dto + 订阅表单 switch(仅存在 PAN115 cookie 账号时提示可用);
- 全局总闸 Setting `msub_self_share_enabled`(默认关);
- 目标账号:一期自动选择——订阅 `accountIds` 中的 PAN115 目标,无则 master PAN115;
- 非 TRANSFER 订阅同样可用(批次转存内部自理,不要求 TRANSFER 模式)。

## 6.5 长番闸门(2026-09-12 拍板)

快照按批固化、每批一链接的形态只适合中短篇:柯南式长番(1200+ 集持续更新)会让分享/挂载数
随更新无限增长,且首批转存瞬时占盘过大。**集数规模超过上限(默认 200,Setting `msub_self_share_max_episodes`,
网页「追剧设置→自有分享集数上限」可配,0=不限)的剧不启用**——自动与手动批次都拦,已有批次照常供播,
新集回归普通模式(上游+搜索)。规模取官方总集数与可看集数的较大者:前瞻拦在播长篇
(官方已登记数百集),观测拦官方数据缺失的存量。多分享聚合本身不受影响(集源行按集聚合,
上游几个分享各贡献各的集号)。

## 7. 边界与风控

- 上游非 115 分享:该集跳过自有化(跨盘字节中转太重),UI/文档注明;
- OPEN115 账号不可用(开放平台无分享 API,`relayOnly` 同因);
- share/send `state=false`:透传 errno/message 落事件(分享数上限/违规内容),下轮重试;
- 验证失败:不删源不删分享(孤儿分享由二期清理),事件报错;
- 多订阅共享上游:各自转存各自分享;`unmountShareIfUnused` 共享守卫已有;
- 删源=rb/delete 彻底删除;若 115 侧回收站仍占空间(账号差异),二期加 delete_code 兜底清理。

## 8. 分期

- **一期(本次)**:115driver 封装、PowerList 端点、Java 编排(批次/验证/删源/入账/豁免/限频/事件/开关/UI)、测试;
- **二期**:孤儿分享清理(需 share/del 抓包)、磁力离线产物自有化(产物已在 115 盘)、主源 self 免重列、回收站 delete_code 兜底、每批体积上限。

## 9. 部署

- PowerList 镜像重建后新端点生效(Java 侧检测端点 404 时报「需升级 PowerList」事件,不炸巡检);
- 115driver 发版(v0.2.7+)后 PowerList 可切正式封装(替换内联实现),不受阻塞。
