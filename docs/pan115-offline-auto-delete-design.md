# 115 离线下载自动清理设计(TTL 删任务+文件 + 永久分享固化)

状态:**已实现**(2026-09-15,v2.2 定案落地;v1 生命周期触发废弃,v2 纯 TTL 经两轮修正:起算点改完成时间、msub 行改**观看进度门禁**)。
实现索引:迁移 V54 + `OfflineDownloadTask` 五列;`OfflineCleanupService`(每日 05:40,触发矩阵/活体检查/追平门禁/固化编排,四盘按 `supportsTaskManagement`/`deletesFilesWithTask` 分叉,123 离线 handler 含两步提交+重登自愈);`Pan115OfflineDownloadHandler.taskStatus/deleteTask`(task_del 契约)与 `ThunderOfflineDownloadHandler` 同款(phase 映射 + task_ids/delete_files);光鸭 AList 删文件路径;`MediaSubscriptionCheckService.selfifyOfflineProduct`(固化转换);`OfflineDownloadService`(短路放行/FAILED 即清/info_hash 落行/completed_time);web-ui DriverAccountView 离线 tab 三控件。
目标用户:离线配额大、重度使用磁力兜底/离线入口的用户。

## 0. 定规(用户拍板)

1. **只删任务没有意义,清理空间才是目的** → 自动清理=任务+文件一起删,TTL 定时,不做生命周期跟踪。
2. **删任务的理由是「离线任务不能重复添加」**(115 同磁力重复提交报 task existed/errno 10008)→ 删任务后同磁力必须能重新提交。
3. 离线信息入库留档:网盘类型、账号、任务 id、成功状态、提交时间、保存路径。
4. 每日定时删除 24 小时前的离线任务(时长可配置)。经两轮修正定案(v2.2):TTL 一刀切**只适用通用即看即走入口**(/parse、/offline_download,无进度可依);**msub 磁力行按观看进度门禁**——覆盖集全部看完(追平)才删,固化开关开启则固化后即删。用户定规原话场景:「离线下载有 40 集,24 小时就删除,他还有很多集没有看」。TTL 起算点=完成/收割时间;不按媒体文件数量调时长(理由见 §5 末)。
5. 115 加开关:离线成功后或删除前,自动创建**永久分享**并把分享地址存库。

## 1. 赖以成立的已验证事实

- **「建永久分享→删源→分享仍活」在本仓库生产验证过**:115 分享是快照固化,建分享后盘内源文件即冗余(`Pan115SelfShareService.removeAll` 注释明示;自有分享流程「转存建永久分享删源」已上线)。两步契约(share/send ignore_warn=1 默认仅 15 天 → 必须 updateshare -1 转永久)已封装在 PowerList 驱动 `/api/fs/share/create`。**磁力产物自有化本就是自有分享设计的二期留项**,本设计即其落地。
- **删除 API 以 info_hash 为键**:`POST lixian.115.com/lixian/?ct=lixian&ac=task_del`,form 重复 `hash=<info_hash>` + `flag`(0=仅任务,1=任务+文件),一次原子删;契约来源 115driver v0.3.x(已验证实现)。app 不存 115 task_id,但任务本就以 info_hash 标识、`offline_download_task.info_hash` 列现成——task_del/查重/重提判定全用它,无需补 task_id 列。幂等:任务不存在视为成功。
  - 实测点:app 现用 `clouddownload.115.com/web/` 端点族,task_del 属 `lixian.115.com` 族,同一 web 会话应通用;若拒签降级试 `clouddownload.115.com/web/?ac=task_del`。
- **提交短路会挡重提**(`OfflineDownloadService.doSubmitMagnet:223-233`):同 urlHash 的 COMPLETED 行直接返回「已有」、PENDING 行返回「进行中」。**清理后若不放行,同磁力永远无法重新提交,违背定规 2** → 清理完成的行必须绕过短路(见 §5)。
- 磁力产物现状:无转存,资源行 `mountPath` 直指 `alist-tvbox-offline` 目录内产物,播放直读;任务与文件今天**永久残留,零清理**。

## 2. 方案总览:两个独立开关

均放现有 `offline_download_config`(StoredConfig JSON,加字段向后兼容):

- **autoDelete**(bool,默认 false)+ **ttlHours**(int,默认 24):每日定时清理离线任务+文件——通用入口按 TTL(完成时间起算),msub 行按追平门禁或固化后即删(见 §5 触发矩阵)。
- **selfShare**(bool,默认 false,仅 cookie PAN115;OPEN115 无分享 API):删除前对仍被订阅引用的磁力产物**先固化永久分享**(建分享→存 URL→资源行切自有分享播放),再删任务+文件 → 清理对播放零影响,内容经由分享快照长期可达。

开关组合语义:
- autoDelete ON + selfShare ON(推荐):收割后下一轮清理即固化+删,不看观看进度,空间立刻回收,内容银行化由分享接管播放,追剧零感知。
- autoDelete ON + selfShare OFF:**追平门禁**——资源行覆盖集全部看完才删;没看完之前文件就是唯一正片,天然保留。弃剧=删订阅或移除资源行(手动移除入口已有)。
- selfShare 单独 ON(未开 autoDelete):固化照做、不删任何东西(纯备份语义),为日后开清理做存量固化铺垫。

## 3. 数据模型:扩展现有表(定规 3 已基本覆盖)

`offline_download_task` 现有列与定规字段的映射:任务 id=`info_hash`(115 任务键)、成功状态=`status`、提交时间=`created_time`、保存路径=`target_path`、账号=`account_id`(join DriverAccount 得网盘类型;离线配置全局单账号,无需冗余列)。

新增列(Flyway V5x,Java 迁移须同步注册 META-INF/services 与 NativeFlywayMigrationConfig):

| 列 | 用途 |
|---|---|
| `cleanup_state` | null / PENDING / DONE / FAILED(清理执行状态,与下载状态正交) |
| `cleanup_time` | 清理完成时间(审计) |
| `completed_time` | 完成/收割时间(TTL 起算点;同步完成与 settle 两路写入;PENDING 落行时为空) |
| `share_url` | 固化分享地址(≤500;外部串入库 abbreviate 规约照守,分享 URL 实际 ~60 字符) |

- **行永不物理删**:月度配额计数、urlHash 查重、FAILED 记忆、pending 闸门都依赖行;清理只置 `cleanup_state=DONE`。
- 资源行侧才是播放的持久载体:固化成功后 `MediaSubscriptionResource.link`=自有分享 URL(与 ShareService 115 解析格式一致),`share_id` 指向自有 Share 行——URL 的持久家在资源行,`share_url` 列是来源留档。
- 重提按 urlHash 原地 upsert 覆盖行(既有语义):`cleanup_state`/`share_url` 随新一次提交重置;被覆盖前固化的旧分享仍在 115 侧存活(孤儿分享,见 §8)。

## 4. 固化转换(删除前固化,不做收割时钩子)

定规 5 允许「离线成功后或删除前」二选一,取**删除前**:转换全部收敛在每日清理任务内,巡检主链路零改动,且天然覆盖功能上线前的存量行(收割时钩子够不着存量)。

对每条 COMPLETED 且仍 LIVE 挂离线目录的磁力行:

1. `createShare`(离线账号,产物目录 `{离线根}/{taskName}`)——**必须用离线配置的那个账号**(产物在该账号盘内,自有分享的 master 优先选号在此不适用);两步契约+快照竞态退避(`enableStorageAwaitingSnapshot`)复用现有封装。
2. 资源行接管:`link`=自有分享 URL、`share_id`=自有 Share 行、`source` 从 SOURCE_MAGNET 改为 self115 同款、mount 切分享挂载(自有分享首批 activate 接管 mount_path 同款)。沿用 self115 巡检豁免(onInvalid 不退役、aux 扫描跳过)——快照不可变,不该被重列/回收。
3. `share_url` 入库留档 → 然后才轮到删除(§5)。
4. 固化失败:本轮跳过不删,文件原地保留,次日重试;连续失败记 WARN(不丢数据的保守侧)。
5. 产物已从离线目录消失/行已退役/订阅已删:无需固化,直接删任务即可。

边界说明:
- **与自有分享 FOLLOW/TRANSFER 互斥不冲突**:互斥根因是批次转存共用「我的追剧」目录会互踩;磁力固化不转存、不动该目录,直接对离线目录产物建分享,TRANSFER-only 的磁力兜底与 FOLLOW-only 的自有分享批次井水不犯河水。
- 分享粒度=每产物一个分享(115 加文件必须新建分享,快照不可变,天然一产物一分享)。分享标题即 taskName(原始资源名),美观化留二期。长番按集磁力会产生较多小分享,量级无害;若要收敛为按剧合并,须先把产物 fs/move 进统一目录——**move 后严禁 task_del flag=1**(115 按 fid 删文件,fid 跨路径有效,会追杀已移走的文件),只能 flag=0,二期再议。
- 分享创建只能用 cookie PAN115 账号;开放平台无分享 API。

## 5. 清理执行(每日定时任务)

新建 `OfflineCleanupService`,自有 `@Scheduled` 每日一次(05:40——用户定规避开 6 点整段高峰:06:00 例行清理/06:05-06:11 签到族/06:30-06:50 全挤在该小时;固化+删除是网络密集型,批量限速防风控)。逐行判定:

| 行形态 | 条件 | 动作 |
|---|---|---|
| FAILED | 115 侧任务残留会挡重提(10008) | **即清,不等 TTL**:提交失败当场 task_del flag=1(提交路径钩子);每日任务兜底扫漏 |
| PENDING(未收割) | 滞留任务白占 115 槽位+app pending 闸门,但**不按 TTL 盲删**——先活体检查 `findTask`(匹配键见下):115 侧已完成且订阅仍在 → 跳过等收割(砍了白瞎整包进度);仍在下载 → 跳过,提交超 `offlinePendingStuckDays`(默认 7 天)才按滞留清;已失败/查无 → task_del flag=1,行置 FAILED 或 DONE |
| COMPLETED,subscriptionId=null(通用 /parse、/offline_download 即看即走) | **完成时间**超 TTL | task_del flag=1 → DONE |
| COMPLETED,msub 行,autoDelete ON + selfShare OFF | **追平门禁**:资源行覆盖集全部已看(取数与 🆕 追平角标同源聚合,只升不降、计数去重;保守回落=最大覆盖集号 ≤ caught_up_episode);或行已退役/订阅已删 | task_del flag=1 → DONE,行放行重提;未追平保留 |
| COMPLETED,msub 行,autoDelete ON + selfShare ON | 无需等进度,收割入账后即可处理 | 先按 §4 固化(仍被引用且未固化者),固化成功或无需固化 → task_del flag=1 → DONE;固化失败跳过次日再试 |
| 任意 | 115 侧任务已不存在(用户手清/别端删) | task_del 报不存在=幂等成功 → DONE |
| autoDelete OFF | — | 零删除(selfShare 单独开时仍做 §4 固化) |

执行细节:
- **放行重提(定规 2 的落点)**:`doSubmitMagnet` 短路处增加 `cleanup_state=DONE` 分支——COMPLETED/PENDING 行若已清理,视同无行,照常重新提交(行被 upsert 覆盖)。FAILED 记忆不因清理解除(坏磁力重复烧配额,语义不变,手动重试本就不受限)。
- 幂等与重试:task_del 失败置 `cleanup_state=FAILED`,每日重试,连续 5 次 WARN 放弃;任务不存在视为成功。
- PENDING 活体检查的匹配键:`saveAttempt` 目前**不落 info_hash**(只存预测名),须补提——magnet 的 btih 提取逻辑在 handler 已有(`xt=urn:btih:`),抽公共方法在服务层落列;ed2k 无 btih,按预测名对 task_lists 匹配,匹配不上只按滞留天数兜底。
- **只删本系统提交的任务**(表内有行、有 info_hash);严禁 `task_clear` 批量清空,会误删用户在 115 客户端自建的任务。
- 删除后 msub 磁力行的退役:走既有收割扫描「产品消失→retireResource」(磁力行 shareId=null,退役只翻行态不删文件),零新路径;selfShare ON 下行已切分享挂载,不受离线目录扫描影响。
- 日志:INFO `offline task cleaned hash=… files=true share=… `;不做 msub 事件,轻量为主。

### 删除时机定案:不按文件数量,也不对 msub 行用一刀切 TTL(2026-09-15 两轮评估)

用户场景:「离线下载有 40 集,24 小时就删除,他还有很多集没有看」。两次候选方案均否决:

- **按媒体文件数量调时长**:数量只是观看进度的粗糙代理——binger 三天看完 40 集、周更党看数月,同一个公式必错一边;且文件数与下载时长、重取成本负相关(单个 80GB 4K remux 只有 1 个文件却最需要时间保护),方向就是反的。而系统**本来就知道精确进度**:资源行覆盖集 × 订阅已看聚合(🆕 追平角标同源,caught_up_episode 只升不降、计数去重),门禁按真实进度判,集数自然隐含其中。
- **msub 行一刀切 TTL**:没看完就删=砍正片(无固化时文件即唯一可播副本)。

定案三分法(即 §5 矩阵):msub+固化 OFF → 追平门禁,看完才删;msub+固化 ON → 固化后即删,分享接管,与进度无关;通用入口行 → 无进度可依,TTL 兜底。首轮评估的另一修正仍有效:TTL 起算点从提交时间改完成时间+PENDING 活体检查,防慢任务被中途砍死。

## 6. Handler 接口(三盘已接,按能力声明分叉)

`OfflineDownloadHandler` 加 `default void deleteTask(account, infoHash, taskName, deleteFiles)`、`default TaskStatus taskStatus(account, infoHash, taskName)`(RUNNING/SUCCEEDED/FAILED/ABSENT)与 **`default boolean supportsTaskManagement()`** 能力声明:

- **Pan115**(true):task_del(hash+flag) 原子删任务+文件;活体检查按 btih/产物名对 task_lists。
- **Thunder**(true):全量任务列表按 btih(params.url 提取)/产物名定位 → DELETE task_ids(+delete_files 连文件);活体检查映射 phase(COMPLETE/ERROR/其它)。
- **123**(managed,`deletesFilesWithTask=false`):两步提交(resolve→submit,任务 id 为 int64 存行 info_hash 位);任务删除有 API(task/delete)但无「连文件删」参数——删任务记录之外,产物文件经内嵌 AList 兜底删;超时抛 `OfflineTaskPendingException` 携带任务 id 落 PENDING 行,活体检查按 id 直查零匹配成本;Bearer token 失效按 DriverAccountService 同款 passport/mail 重登自愈回写。
- **光鸭**(false):无任务删除契约(PowerList 驱动侧也是空壳)——但重复提交直接建新任务、无「任务已存在」限制,任务记录留存无害;清理走内嵌 AList 删产物文件(`{离线根}/{taskName}`)回收空间;PENDING 只按滞留天数兜底(预测产物名对不上任务列表,防误判查无砍死在途任务)。

固化分享仍仅 cookie PAN115(其它盘开着开关忽略,UI 也只在 115 显示);追平门禁/TTL/FAILED 即清全盘同权;`deletesFilesWithTask` 能力决定删任务后是否由清理调度经 AList 补删产物文件(123/光鸭)。

## 7. 配置与 UI

- `StoredConfig` 扩展:`autoDelete`(false)、`ttlHours`(24)、`selfShare`(false);旧 JSON 无字段反序列化为默认值,天然兼容。
- web-ui 离线下载设置 tab(DriverAccountView 现有配置区)加:开关「自动清理离线任务和文件」、数字「通用入口保留时长(小时)」(只管即看即走内容,msub 内容按追平/固化时机不受此值影响)、开关「清理前固化永久分享」(仅 115 显示)+ 联动提示:selfShare 关闭时提示「追平后才删除文件,开启固化可收割后立即回收空间」。
- 滞留 PENDING 阈值:AppProperties `offlinePendingStuckDays`(默认 7 天),提交超期且 115 侧无进展才按滞留清。

## 8. 已知缺口(与自有分享共用,不在本期)

- **孤儿分享清理**:115 `share/del` 未抓包(自有分享二期同样留项)。固化分享会随清理/重提累积,不占空间但占分享列表;订阅删除时可顺手 `unmountShareIfUnused` 摘挂载,分享本体清理等契约。
- **转存回重物化**:「自分享能否转存回自己盘」仍未实测。若验证通过,重提可优先从已固化分享 `shareSave` 秒传回盘,零离线配额消耗、瞬时完成——比重新离线下载优雅得多,二期首选。
- 按剧合并分享(须 move+flag=0)、分享标题美观化、光鸭任务删除契约(PowerList 驱动侧也是空壳:driver.go:1175 "no delete API available in reference client" 直接 return nil,参考客户端都无此 API,抓包到再升级为 managed)。

## 9. 测试要点

- `Pan115OfflineDownloadHandlerTest`:task_del 契约(重复 hash 字段+flag、cookie/UID 头、state/errno、「不存在=成功」)。
- `OfflineCleanupServiceTest`:§5 矩阵逐行(FEILED 即清、PENDING 超期、通用行 TTL、selfShare 门禁「未固化不删」、固化失败次日重试、autoDelete OFF 零调用、幂等/重试上限);115 driverType 才执行。
- 固化转换:行接管字段(link/share_id/source/mount)、巡检豁免生效、快照竞态退避复用、产物缺失跳固化直删任务。
- 放行重提:cleanup DONE 的 COMPLETED/PENDING 行短路绕过、FAILED 记忆保留、重提 upsert 重置清理列。
- 坑对照:Mockito 不执行接口 default 方法(桩须显式)、服务类单构造器、行不物理删(规避 JPA 删后 save 复活)、资源行须 setId 否则 NPE 歪打正着。
