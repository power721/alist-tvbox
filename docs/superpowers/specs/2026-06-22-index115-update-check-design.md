# 115 分享索引更新检查（只读）

## 背景
高级设置页"115索引"行目前只有"下载"按钮（`POST /api/index115/update`，会真正下载+解压）。用户希望页面能**只读检查**远端是否有新版本并展示，不触发下载。

现有基础：
- `Index115VersionClient.fetch()` 从 `https://d.har01d.cn/115.version.txt` 取 `shareCode:receiveCode`。
- 本地已下载版本存于 setting `index115.share_code`。
- `GET /api/index115/status` 仅返回 `{hasAccount}`。
- 前端 `ConfigView.vue` 的"115索引"行在 `has115Account` 时显示。

## 决策
- **触发**：页面加载、`has115Account` 为真时自动调用检查。
- **展示**：`el-tag` 徽标 + 本地/远端版本号；保留"下载"按钮。
- **端点形态**：新增 `GET /api/index115/check`（方案 A，不改动 `/status`，关注点分离）。

## 设计

### 后端
1. `dto/Index115CheckResult.java`（record，顶层类，置 `dto/`）：
   `(boolean hasAccount, boolean hasUpdate, String localVersion, String remoteVersion, String error)`
2. `Index115Service.check(): Index115CheckResult`
   - `!has115Account()` → `{false, false, "", "", null}`
   - 否则 `local = settingRepository.findById("index115.share_code").map(getValue).orElse("")`
   - `ref = versionClient.fetch()`；`ref == null` → `{true, false, local, "", "无法获取远端版本"}`
   - 否则 `remote = ref.shareCode()`；`hasUpdate = !local.equals(remote)`；返回 `{true, hasUpdate, local, remote, null}`
3. `Index115Controller`：`@GetMapping("/check")` 返回 `index115Service.check()`。鉴权沿用 `/api/**` → ADMIN/CLIENT。

### 前端 `web-ui/src/views/ConfigView.vue`
- 响应式：`index115Check`（对象）、`index115Checking`（bool）。
- `onMounted`：现有 `/api/index115/status` 返回 `hasAccount=true` 后链式调用 `GET /api/index115/check`，写入 `index115Check`。
- "115索引"行 UI：
  - 徽标 `el-tag`：加载中（`index115Checking`）→ info "检查中"；`error` → danger "检查失败"；`hasUpdate` → warning "有更新"；否则 success "已是最新"。
  - 灰色小号 span：`当前: {localVersion || '未下载'}　最新: {remoteVersion || '-'}`，原值显示，长则 CSS 截断。
  - 保留现有"下载"按钮。

### 测试
扩充 `Index115ServiceTest.check()` 五分支：
- 无账号 → hasAccount=false
- local==remote → hasUpdate=false
- local!=remote → hasUpdate=true
- fetch 返回 null → error 非空、hasUpdate=false
- 本地空（未下载）+ remote 存在 → hasUpdate=true

### Native Image（CLAUDE.md）
新 record 在 `dto/`，`Main.java` 已扫描该包；打包前执行：
```
mvn compile
java -cp target/classes cn.har01d.alist_tvbox.Main
```
以重生成 `reflect-config.json`。

## 非目标
- 不做定时轮询/缓存（与 `/api/versions` 页面加载即拉取的现有模式一致）。
- 不加重试按钮（刷新页面即可）。
- 不改动 `update()` 下载流程。
