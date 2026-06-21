# 用户页面 — 当前用户登录会话

**日期**: 2026-06-21
**范围**: 在 `/user` 用户页展示当前用户的活跃登录会话（登录时间、IP、UA/设备），并支持注销指定会话。

## 目标与非目标

**目标**
- 用户页展示当前用户全部**活跃会话**（复用现有 `session` 表，登出/过期后记录即删除）。
- 每条会话显示：登录时间、IP、UA（原始）+ 友好设备摘要（浏览器·操作系统）、过期时间、当前会话标记。
- 允许注销（revoke）任意一条自己的会话（含当前会话——注销当前 = 自身登出）。

**非目标**
- 不做持久化「登录历史」（已登出/过期不保留）。
- 不做「一键注销其他所有会话」按钮。
- 不做 IP 反查地理位置（GeoIP，对 GraalVM 原生镜像成本过高）。
- 不做管理员查看他人会话（仅当前用户自查）。

## 现状（已勘探）

- 认证：自定义 token（UUID），存 `session` 表。`Session` 实体字段：`id, token, userId, username, role, expireTime, createTime`。
- 限制：每用户最多 5 个并发会话；token 30 天过期；登出/过期删除记录。
- 登录入口：`UserController.login(LoginDto)` → `UserService.generateToken(user)` → `SessionTokenService.encodeToken(...)`。当前**未捕获 IP/UA**。
- IP 工具已存在：`Utils.getClientIp(HttpServletRequest)`。UA 需 `request.getHeader("User-Agent")`。
- 当前用户/会话解析：`SecurityContextHolder.getContext().getAuthentication()`，`getPrincipal()`=username，`getCredentials()`=token。
- Flyway 迁移位置（`application.yaml:32`）：`classpath:db/migration/{vendor},classpath:db/migration/common,classpath:db/migration/current`。`common/` 跨 H2/MySQL/PostgreSQL 生效（V5 即在 common）。下一可用版本号：**V6**。
- 前端用户页：`/user` → `web-ui/src/views/UserView.vue`（当前仅用户名/密码更新表单）。表格范式见 `UsersView.vue`（Element Plus `el-table`）。API 在 `web-ui/src/services/account.service.ts`（直接 axios）。无 vue-i18n，中文硬编码。

## 设计

### 后端

**1. Flyway 迁移** — 新建 `src/main/resources/db/migration/common/V6__add_session_login_info.sql`：
```sql
ALTER TABLE session ADD COLUMN login_ip VARCHAR(45);
ALTER TABLE session ADD COLUMN user_agent VARCHAR(512);
```
两条单列 `ADD COLUMN` 语句，H2/MySQL/PostgreSQL 均合法（避免多列逗号语法在 H2 的兼容风险）。两列均可空（兼容历史数据）。

**2. `Session` 实体** — 加字段（Lombok `@Data`）：
```java
@Column(name = "login_ip")
private String loginIp;
@Column(name = "user_agent")
private String userAgent;
```

**3. 登录时捕获 IP/UA** —
- `UserController.login(LoginDto, HttpServletRequest)`：取 `Utils.getClientIp(request)` 与 `request.getHeader("User-Agent")`，透传 `UserService.generateToken(user, loginIp, userAgent)` → `SessionTokenService.encodeToken(username, role, loginIp, userAgent)` 写入新列。
- 截断 UA 到 512 字符防止溢出。

**4. DTO** — 新建 `dto/SessionDto`（顶层类，符合项目 DTO 规范）：
```java
private Integer id;
private String username;
private String role;
private String loginIp;
private String userAgent;     // 原始 UA
private String browser;       // 解析结果
private String os;            // 解析结果
private Timestamp loginTime;  // 映射自 createTime
private Timestamp expireTime;
private boolean current;      // 是否当前请求所在会话
```

**5. UA 解析** — 新建 `util/UserAgentParser`（纯静态方法，正则，无第三方依赖）：
- `parseBrowser(String ua)` → 如 `Chrome 120` / `Safari` / `Firefox` / `Edge` / 未知。
- `parseOs(String ua)` → 如 `Windows` / `macOS` / `Android` / `iOS` / `Linux` / 未知。
- 空值/未知返回空串或"未知"。在构建 `SessionDto` 时调用（读时解析，不落库）。

**6. 新端点**（`UserController`，均按当前认证用户过滤，**严禁跨用户**）：
- `GET /api/accounts/sessions` → `List<SessionDto>`：按 `SecurityContextHolder` 当前 username 查 `SessionRepository.findAllByUsername`，逐条映射为 `SessionDto`，`current` 由比较该会话 `token` 与当前请求 token（`getCredentials()`）得出。
- `DELETE /api/accounts/sessions/{id}` → 注销指定会话：按 id 查 session，校验其 `username` == 当前 username，不匹配则抛 `UserUnauthorizedException`（403/拒绝），匹配则删除。删除当前会话等价登出（前端可后续跳登录，本期不强制跳转）。

**7. 权限** — 两个端点沿用现有受保护路由（`TokenFilter` 已覆盖 `/api/accounts/*` 除 login 外）。

**8. GraalVM 原生镜像** —
- `SessionDto` 位于 `dto/`（`Main.java` 已扫描），新增后须重生成 reflect-config：
  ```bash
  mvn compile && java -cp target/classes cn.har01d.alist_tvbox.Main
  ```
- 无新资源文件，无需改 resource-config。`Session` 实体已注册，加字段无影响。

### 前端

**1. `web-ui/src/services/account.service.ts`** — 新增：
- `getSessions()` → `GET /api/accounts/sessions`
- `revokeSession(id)` → `DELETE /api/accounts/sessions/{id}`

**2. `web-ui/src/views/UserView.vue`** — 凭据表单下方新增会话区：
- `el-table` 列：登录时间(`loginTime`) / 设备(`browser · os`，原始 UA 作 `el-tooltip`) / IP(`loginIp`) / 过期时间(`expireTime`) / 操作(注销按钮)。
- 当前会话行：`current=true` 显示"当前"标签；其"注销"按钮仍可点（=登出自己）。
- 注销交互：`el-message-box` 二次确认 → 调 `revokeSession` → 刷新列表。
- 进入页面与每次操作后调用 `getSessions()` 刷新。
- 样式沿用 `UsersView.vue`（`border`、`width` 等）。

## 数据流

```
用户登录 → UserController.login(LoginDto, req)
        → UserService.generateToken(user, ip, ua)
        → SessionTokenService.encodeToken(... ip, ua) → 写 session(login_ip, user_agent)

用户页打开 → GET /api/accounts/sessions
          → SessionRepository.findAllByUsername(currentUsername)
          → 映射 SessionDto（UserAgentParser 解析 + current 标记）

注销会话 → DELETE /api/accounts/sessions/{id}
        → 校验归属 → SessionRepository.deleteById
```

## 错误处理

- IP/UA 缺失（代理/无 UA 请求）：两列允许 null；`UserAgentParser` 对 null 返回"未知"，UI 显示"—"。
- 跨用户注销（伪造 id）：后端按 username 校验，不符抛 `UserUnauthorizedException`。
- 会话 id 不存在：404 或按"无可注销"处理（建议 404）。

## 测试

- `UserAgentParser` 单元测试：覆盖 Chrome/Edge/Firefox/Safari、Windows/macOS/Linux/Android/iOS、空值、超长 UA。
- （若现有集成测试模式允许）`/api/accounts/sessions` GET/DELETE 端点测试：含跨用户注销被拒、当前会话标记正确。

## 已确认决策

1. 注销当前会话 = **放行**（语义一致，即自身登出）。
2. **不做**"一键注销其他全部"按钮。
3. 设备列展示解析后 `browser · os`，原始 UA 放 tooltip。

## 影响面 / 回归

- `session` 表加两列：历史数据为空，无破坏性。
- `login()` 签名变化：仅内部调用（`UserController`→`UserService`→`SessionTokenService`），无外部 API 契约变更。
- reflect-config 重生成后须本地 `mvn clean package -Pnative` 验证（CLAUDE.md 要求）。

## 未决问题

无（3 项已确认）。
