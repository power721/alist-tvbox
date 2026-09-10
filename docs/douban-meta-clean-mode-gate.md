# 纯净版豆瓣 meta 幽灵路径门禁

## 背景

1.75.0（`b1f0334b`）把豆瓣电影数据从 xiaoya 专属放开到全部署模式灌库（基线 data.sql + 增量 diff），其中 **meta 表**连带灌入：数据集 meta 行携带小雅挂载布局的路径（`/每日更新`、`/我的115分享`、`/电影`、`/电视剧`、`/动漫`…，线上实证 66,936 行全部如此、site_id 恒为 1）。

纯净版（docker 标准镜像等）没有内置小雅分享数据，这些路径在本地 AList 上不存在。而 `/vod1`（订阅配置注入的 VOD1_URL）type=0 的搜索直接查 meta 表（`metaRepository.findByPathContains`），type=0 的「🎥站名」分类浏览（`findByPathStartsWith("/")`）也列全表——幽灵行变成可搜索、可浏览但不可播放的条目。

当时全模式灌库的提交已评估过「非 xiaoya 剥离」：基线通道重构 + diff 双路过滤 + native 脚本层不可分，故 meta 表全模式同灌。本修复在**应用层**收口，不动打包链路。

## 门禁信号

`SiteService.hasXiaoyaData()` —— **镜像级 profile ∨ 用户级站点标志**，任一为真即视为小雅数据布局在位：

1. **镜像级** `app.xiaoya`（AppProperties 绑定）：小雅系镜像 CMD 恒激活 `xiaoya` profile（`production,xiaoya` / `production,xiaoya,host`）→ true；纯净镜像（`production,docker`）→ false。此前该配置项在 yaml 里是未绑定的死值，本次激活。
2. **用户级** `SiteRepository.existsByXiaoyaTrue()`：站点表 xiaoya 标志，网页可改（`SiteService.save` 尊重 dto），`updateSite` 重启只补 token 不覆盖标志。**用户自行修改标志属于自主行为，后果自负**（纯净镜像用户自挂小雅数据后勾选即生效；改错=自己引入幽灵路径）。

部署形态由镜像的 `INSTALL` 环境变量决定（entrypoint 按 case 分派初始化脚本）：

| INSTALL | 形态 | 初始化 | 镜像 profile |
|---|---|---|---|
| `new` / `docker` / `native`（含 `*` 兜底） | **纯净版** | `init-alist.sh` | docker（app.xiaoya=false） |
| `xiaoya` / `hostmode` / `native-host` | 小雅版 | `init-xiaoya.sh` | xiaoya（app.xiaoya=true） |

镜像级信号兜底「纯净卷被小雅镜像复用」形态：`SiteService` 只在站点表为空时建站，复用卷的无标志「本地」站点不会被改回，单看站点标志会让小雅镜像持续误判纯净 → meta 数据集被持续跳过/清理。

## 改动

diff 只支持 json 格式（sql diff 不再导入，存量 `atv/sql/` 内容忽略；基线 `data.sql` 仍是 SQL，与 diff 是两条通道）。

| 入口 | 行为（无小雅数据布局时） |
|---|---|
| `DoubanService.applyJsonDiff`（json diff，唯一 diff 通道） | 跳过 `metaDeletes`/`metaUpserts`；MOVIE 行照常（元数据与路径无关，纯净版刮削/片单 db: 详情在用）；版本照常推进，movie_diff 记账只含实际应用行数 |
| `MovieDataSeeder`（MySQL/PG 基线） | 跳过 meta 语句（`H2SqlConverter.isMetaStatement`，基线 data.sql 行级过滤） |
| `DoubanService.cleanupDatasetMetas`（启动，setup 内 fixMetaId 之后） | `DELETE FROM meta WHERE id < 500000` 兜住存量污染（H2 基线走 spring.sql.init 先于应用启动加载，只能事后清理）；幂等，每启跑一次，命中才打日志 |

MOVIE/ALIAS 行不受门禁影响（纯元数据，无路径语义）。

## id 分界依据

`fixMetaId` 把 meta 的 table generator 本地起点抬到 500000，数据集行自带 id 恒低于此分界（线上 max id 114,730）。本地生成行（TMDB scrape、网页手动添加）经 generator 恒从 500000 起，不受清理影响。分界抽取为 `DoubanService.DATASET_META_ID_LIMIT` 常量两处共用。

若数据集行数将来逼近 500000，本地/数据集 id 会相撞——那是 `fixMetaId` 既有约定的前题问题，需在生产端（xiaoya-douban）处理，与本门禁无关。

## 语义与边界

- **纯净版**：meta 表只剩本地行（scrape/手动），搜索与 type=0 浏览不再返回幽灵路径。movie 表照常同步，豆瓣刮削、片单 `db:{id}` 详情不受影响。
- **小雅版**：镜像级信号恒真，门禁恒关，零变化。
- **纯净版后续手工接入小雅分享数据**：把对应站点勾上「小雅」标志即生效——此后增量 diff 的 meta 行开始落库；基线 meta 行已被清理、不自动回补（重灌走既有基线机制：镜像 base_version 变更时 `update_movie` 重解包 data.sql 全量重载，或手动删 `/data/atv/base_version` 强制触发）。
- **误报面**：纯净版用户恰好自挂同名根目录（如自建 `/电影`）时，数据集行仍不会灌入（门禁看部署形态不看路径）——与 1.75.0 之前的行为一致，无回归。

## 模式切换（小雅 ↔ 纯净）数据不丢

- **同一数据卷切换**（update 脚本 `-d` 同目录）：atv 库不重置（`restore_database` 只认显式备份 zip），站点表「丫仙女 xiaoya=true」随库保留 → 门禁恒关 → cleanup 从不执行，meta 数据集原样保留；AList 数据同卷也在，路径仍真实。
- **不同数据卷**（update_new.sh 默认 `$PWD/data`、update_xiaoya.sh 默认 `/opt/alist-tvbox`，本就分卷）：两套独立数据，小雅卷从未被纯净侧碰过。
- **纯净卷被小雅镜像复用**：镜像级 `app.xiaoya=true` 使门禁关闭，增量 diff 的 meta 照常落库；唯纯净阶段已被清理的基线 meta 需等 base_version 变更重灌（见上）。此形态已由双信号兜底。
- meta 数据集本质是派生数据：镜像内置 data.zip（基线）+ 远端 diff.zip（增量）随时可全量重建，不存在不可恢复的丢失。

## 测试

- `SiteServiceTest`：门禁双信号取或（镜像 profile 兜底 / 用户标志生效 / 纯净部署开启）。
- `MovieDiffApplyTest`：纯净版 json diff 跳 META 增删且版本推进/记账正确；瞬态失败整文件重放至多 3 次；启动清理按 id 分界删除；小雅部署不清理（setUp 默认 stub `hasXiaoyaData()=true` 保住存量语义）。
- `MovieDataSeederTest`：纯净版基线过滤 META 语句、MOVIE 语句照常转换 seed。
- sql diff 用例已随 sql 导入路径一并移除（json-only，用户定规）。
