# 潇洒本地包动态地址解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把写死的潇洒 single.json/version.txt URL 换成运行时从 `https://d.har01d.cn/xs.txt` 读取，上游换地址不再发版。

**Architecture:** 消费端 `FileDownloader` 现拉 `xs.txt`（内容=single.json 地址）→ 推导 version.txt。解析与推导逻辑抽成 package-private static 纯函数以可单测；HTTP 胶水层用 compile + 全量测试 + 人工 smoke 验证。

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, Mockito, AssertJ, HttpURLConnection。

## Global Constraints

- Java 21 / Spring Boot 4；4 空格缩进。
- 最小补丁、小 diff、**不改公开签名**（`getXsVersion()` 签名与返回契约不变：失败返回 `""`）。
- 不引入新依赖（不加 MockWebServer）。
- 每个任务结束提交；分支 `feat/xs-dynamic-url`；commit message 结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`。
- 范围仅消费端；生产端 xs.txt 由作者另行发布。

## File Structure

- Modify: `src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java` — 删 2 常量、加 1 常量、加 3 方法（2 纯函数 + 1 HTTP 包装）、改写 2 调用点。
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/FileDownloaderTest.java` — 为 2 个纯函数加单测（现有内容全是注释，不动）。

---

### Task 1: `deriveVersionUrl` 纯函数（TDD）

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java`（新增 static 方法，置于 `getXsDownloadUrl` 之后 L349 附近）
- Test: `src/test/java/cn/har01d/alist_tvbox/service/FileDownloaderTest.java`

**Interfaces:**
- Produces: `static String deriveVersionUrl(String singleUrl)` — 由 single.json 地址推导同目录 version.txt。

- [ ] **Step 1: 写失败测试**

在 `FileDownloaderTest.java` 顶部 import 区加：
```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```
在类内（注释块之外、`}` 之前）加：
```java
@Test
void deriveVersionUrl_swapsSingleJsonForVersionTxt() {
    assertThat(FileDownloader.deriveVersionUrl("https://oss-v1.wangmeipo.cn/236/single.json"))
            .isEqualTo("https://oss-v1.wangmeipo.cn/236/version.txt");
}

@Test
void deriveVersionUrl_dropsQueryWhenTakingDirname() {
    assertThat(FileDownloader.deriveVersionUrl("https://x/236/single.json?v=1"))
            .isEqualTo("https://x/236/version.txt");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=FileDownloaderTest`
Expected: 编译失败 `cannot find symbol method deriveVersionUrl`。

- [ ] **Step 3: 写最小实现**

在 `FileDownloader.java` 的 `getXsDownloadUrl()` 方法（L333-349）之后加：
```java
static String deriveVersionUrl(String singleUrl) {
    int idx = singleUrl.lastIndexOf('/');
    return (idx >= 0 ? singleUrl.substring(0, idx) : singleUrl) + "/version.txt";
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=FileDownloaderTest`
Expected: PASS（2 tests）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java src/test/java/cn/har01d/alist_tvbox/service/FileDownloaderTest.java
git commit -m "feat: add deriveVersionUrl helper for xs version url

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: `parseXsSingleUrl` 纯函数（TDD）

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java`（在 Task1 新增的 `deriveVersionUrl` 之后加 static 方法）
- Test: `src/test/java/cn/har01d/alist_tvbox/service/FileDownloaderTest.java`

**Interfaces:**
- Produces: `static String parseXsSingleUrl(String text)` — 取 xs.txt 文本首个非空 trim 行；空则抛 `IllegalStateException`。

- [ ] **Step 1: 写失败测试**

在 `FileDownloaderTest.java` 类内追加：
```java
@Test
void parseXsSingleUrl_returnsFirstNonEmptyLine() {
    assertThat(FileDownloader.parseXsSingleUrl("https://x/236/single.json\n"))
            .isEqualTo("https://x/236/single.json");
}

@Test
void parseXsSingleUrl_trimsAndSkipsBlankLines() {
    assertThat(FileDownloader.parseXsSingleUrl("\n  https://x/236/single.json  \n"))
            .isEqualTo("https://x/236/single.json");
}

@Test
void parseXsSingleUrl_throwsOnEmpty() {
    assertThatThrownBy(() -> FileDownloader.parseXsSingleUrl(""))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=FileDownloaderTest`
Expected: 编译失败 `cannot find symbol method parseXsSingleUrl`。

- [ ] **Step 3: 写最小实现**

在 `FileDownloader.java` 的 `deriveVersionUrl` 之后加：
```java
static String parseXsSingleUrl(String text) {
    if (text != null) {
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
    }
    throw new IllegalStateException("xs.txt 内容为空");
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=FileDownloaderTest`
Expected: PASS（5 tests）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java src/test/java/cn/har01d/alist_tvbox/service/FileDownloaderTest.java
git commit -m "feat: add parseXsSingleUrl helper for xs index

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 接线 + 清理旧常量

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java`

**Interfaces:**
- Consumes: Task1 `deriveVersionUrl`、Task2 `parseXsSingleUrl`。
- 无新单元测试（被测纯逻辑已在 Task1/2 覆盖；本任务是 HTTP 胶水，靠 compile + 全量测试 + 人工 smoke 验证）。

- [ ] **Step 1: 改常量（L52-53）**

把：
```java
    private static final String XS_VERSION_URL = "https://oss-v1.wangmeipo.cn/236/version.txt";
    private static final String XS_SINGLE_URL = "https://oss-v1.wangmeipo.cn/236/single.json";
    private static final String XS_USER_AGENT = "okhttp/5.3.2";
```
改为：
```java
    private static final String XS_INDEX_URL = BASE_URL + "xs.txt";
    private static final String XS_USER_AGENT = "okhttp/5.3.2";
```

- [ ] **Step 2: 加 HTTP 包装方法**

在 `parseXsSingleUrl` 之后加：
```java
private String resolveXsSingleUrl() throws IOException {
    return parseXsSingleUrl(getRemoteText(XS_INDEX_URL, XS_USER_AGENT));
}
```

- [ ] **Step 3: 改写 `getXsVersion()`（L324-331）**

把：
```java
    public String getXsVersion() {
        try {
            return getRemoteText(XS_VERSION_URL, XS_USER_AGENT).trim();
        } catch (IOException e) {
            log.warn("getXsVersion IOException", e);
        }
        return "";
    }
```
改为（catch 扩为 `Exception`，保留"失败返回空串"契约，覆盖 `parseXsSingleUrl` 的 `IllegalStateException`）：
```java
    public String getXsVersion() {
        try {
            return getRemoteText(deriveVersionUrl(resolveXsSingleUrl()), XS_USER_AGENT).trim();
        } catch (Exception e) {
            log.warn("getXsVersion failed", e);
        }
        return "";
    }
```

- [ ] **Step 4: 改写 `getXsDownloadUrl()`（L333-334）**

把方法体首行：
```java
        String json = getRemoteText(XS_SINGLE_URL, XS_USER_AGENT);
```
改为：
```java
        String json = getRemoteText(resolveXsSingleUrl(), XS_USER_AGENT);
```
（其后的 single.json 遍历 `本地包/点击下载 → url` 原样保留。）

- [ ] **Step 5: 编译**

Run: `mvn compile`
Expected: `BUILD SUCCESS`，无 `XS_VERSION_URL` / `XS_SINGLE_URL` 残留引用（grep 确认）：
```bash
grep -n "XS_VERSION_URL\|XS_SINGLE_URL" src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java
```
Expected: 无输出。

- [ ] **Step 6: 跑全量测试确认无回归**

Run: `mvn test -Dtest=FileDownloaderTest`
Expected: PASS（5 tests）。再跑 `mvn test` 全量确认无回归。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/FileDownloader.java
git commit -m "feat: resolve xs urls from d.har01d.cn/xs.txt at runtime

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Deferred verification（需作者先发布 xs.txt）

纯逻辑已由单测覆盖；**端到端 live smoke 依赖 `https://d.har01d.cn/xs.txt` 已发布**（作者每日进程上线后）：
1. `curl -s https://d.har01d.cn/xs.txt` 应返回单行 single.json 地址。
2. 运行实例触发「同步文件」（`POST /api/cat/sync`）或查 `GET /xs/version`，确认 remote 版本正常返回、xs 包成功更新。

发布前不要把 live smoke 当作通过条件——那不是代码问题。

## Self-Review

- **Spec 覆盖**：删旧常量、加 XS_INDEX_URL、resolveXsSingleUrl、deriveVersionUrl、改写 getXsVersion/getXsDownloadUrl、不保留硬编码兜底、重试沿用 executeWithRetry、前端/DTO 不动 —— 均有对应 step。
- **占位符**：无 TBD/TODO；所有代码 step 含完整代码。
- **类型一致**：`deriveVersionUrl(String)->String`、`parseXsSingleUrl(String)->String`、`resolveXsSingleUrl()->String throws IOException`，Task3 调用签名一致。
