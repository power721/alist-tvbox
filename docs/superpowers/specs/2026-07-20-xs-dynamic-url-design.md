# 潇洒本地包动态地址解析 — 设计文档

- 日期: 2026-07-20
- 状态: 已批准（待 spec 复核）
- 分支: `feat/xs-dynamic-url`
- 范围: alist-tvbox 消费端 only

## 1. 背景

潇洒本地包的下载地址（single.json）与版本地址（version.txt）目前写死在 `FileDownloader.java:52-53`。上游换地址频繁——git 历史已改 3 次（`pizazz.s3.bitiful.net` → `9877.kstore.space` → `oss-v1.wangmeipo.cn/236`），每次都得发版。

## 2. 目标

- 消除写死的 single.json / version.txt URL。
- 地址变更时无需发版、无需 per-instance 配置。
- 解决「首次下载」「长期未启动」两个边角。

## 3. 非目标

- 不做生产端（每日抓取上游、发布 xs.txt 的进程——作者另行维护，不在本仓库）。
- 不改前端、不改 DTO/entity。
- 不解析 api.json 的 `sites[]` 结构（本方案只读纯文本 xs.txt）。

## 4. 方案概述

引入一个**稳定入口地址** `https://d.har01d.cn/xs.txt`（作者自有服务器，与已有 `BASE_URL` 同域）。xs.txt 内容 = 上游 api.json 里 `sites[].ext`（即 single.json 地址），由作者的每日进程刷新。消费端现拉 xs.txt → 推导出 single.json + version.txt。

两个边角由此自动解决：

- **首次下载**：xs.txt 在稳定服务器，装机即可拉。
- **长期未启动**：作者每日刷新 xs.txt，实例读到的永远是最新。

## 5. 现状（关键事实）

- 写死位置：`FileDownloader.java` L52 `XS_VERSION_URL`、L53 `XS_SINGLE_URL`。无 `@Value` / `AppProperties` / DB 配置。
- 消费点：`getXsVersion()` (L324)、`getXsDownloadUrl()` (L333，拉 single.json 后遍历 `本地包/点击下载` 取 zip)。
- 触发：手动「同步文件」→ `/api/cat/sync` → `syncCat()` → `runTask("xs")` → `downloadXsWithRetry` → `downloadXs`。无定时任务。
- 重试：`executeWithRetry`（3 次 / 5s）。
- 版本展示：`XsConfigController` `/xs/version` 返回 `{local, remote}`。
- `BASE_URL = "https://d.har01d.cn/"` 已存在于 L48。

## 6. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 改动范围 | 只改消费端 | 生产端是作者独立进程 |
| xs.txt 格式 | ext 原值（single.json URL），version.txt 推导 | 生产端最省事，单值 |
| 实现方式 | A+ 原地改 + 私有 helper | 最小 diff |

## 7. 详细设计

### 7.1 常量

```java
// FileDownloader.java
- private static final String XS_VERSION_URL = "https://oss-v1.wangmeipo.cn/236/version.txt";
- private static final String XS_SINGLE_URL  = "https://oss-v1.wangmeipo.cn/236/single.json";
+ private static final String XS_INDEX_URL = BASE_URL + "xs.txt";   // 复用 L48 BASE_URL
  private static final String XS_USER_AGENT = "okhttp/5.3.2";       // 保留
```

### 7.2 新增私有方法

```java
/** 拉 xs.txt → 取首个非空行 → 即 single.json 地址。 */
private String resolveXsSingleUrl() {
    String text = getRemoteText(XS_INDEX_URL, XS_USER_AGENT);
    for (String line : text.split("\\R")) {
        String s = line.trim();
        if (!s.isEmpty()) return s;
    }
    throw new IllegalStateException("xs.txt 内容为空");
}

/** 由 single.json 地址推导同目录 version.txt（取 dirname，不假设文件名）。 */
private String deriveVersionUrl(String singleUrl) {
    int idx = singleUrl.lastIndexOf('/');
    return (idx >= 0 ? singleUrl.substring(0, idx) : singleUrl) + "/version.txt";
}
```

### 7.3 改写调用点（签名不变）

```java
getXsVersion():     // 原 L324
    return getRemoteText(deriveVersionUrl(resolveXsSingleUrl()), XS_USER_AGENT).trim();

getXsDownloadUrl(): // 原 L333 — 仅数据源 XS_SINGLE_URL → resolveXsSingleUrl()
                    //   其后现有 single.json 遍历（本地包/点击下载 → zip）原样保留
```

调用方（`downloadXs` / `XsConfigController`）无感，前端零改动。

## 8. 边角与错误处理

- **重试**：沿用 `executeWithRetry`；xs.txt 拉取失败 → 抛异常 → 整个 `downloadXs` 重试。
- **不保留硬编码兜底**：旧 URL 删除，不静默回退过期地址（避免掩盖问题、重新引入过期）。`d.har01d.cn` 不可用时 sync 失败，作者修服务器即可——符合「发现逻辑集中一处」。
- **小冗余**：`downloadXs` 中两次拉 xs.txt（version + download 各一）；小文本请求，可接受。
- **可选（默认不做）**：本地缓存上次 xs.txt，`d.har01d.cn` 暂不可用时回退。YAGNI，作者需要再加。

## 9. 测试

- `deriveVersionUrl`：single.json→version.txt、trailing slash、无文件名、query param 边界。
- `resolveXsSingleUrl`：多行 / 前导空白 / 空内容。
- 现有 `FileDownloader` 若有 HTTP mock 测试，补 xs.txt 用例（planning 阶段确认有无）。

## 10. 范围外

- 生产端每日进程（作者的独立进程）。
- 前端、DTO/entity 改动（xs.txt 是纯文本，不碰 api.json 的 `sites[]` 结构）。

## 11. 未决问题

无。
