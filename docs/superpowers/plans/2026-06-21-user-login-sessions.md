# 用户登录会话展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `/user` 用户页展示当前用户的活跃登录会话（登录时间、IP、UA/设备、过期时间、当前标记），并支持注销指定会话。

**Architecture:** 扩展现有 `session` 表，新增 `login_ip` / `user_agent` 列；登录时从 `HttpServletRequest` 捕获并经 `UserService.generateToken → TokenService.encodeToken` 写入。新增 `GET/DELETE /api/accounts/sessions` 端点，后端轻量正则解析 UA。前端 `UserView.vue` 新增 Element Plus 表格。

**Tech Stack:** Spring Boot 3 / Java 21 / Flyway / JPA / Lombok / JUnit 5 + Mockito；Vue 3 + TypeScript + Element Plus + axios。

**Spec:** `docs/superpowers/specs/2026-06-21-user-login-sessions-design.md`

---

## File Structure

**Backend (create):**
- `src/main/resources/db/migration/common/V6__add_session_login_info.sql` — 加列迁移（跨 H2/MySQL/PG）。
- `src/main/java/cn/har01d/alist_tvbox/dto/SessionDto.java` — 会话视图 DTO。
- `src/main/java/cn/har01d/alist_tvbox/util/UserAgentParser.java` — UA 正则解析（browser/os）。
- `src/test/java/cn/har01d/alist_tvbox/util/UserAgentParserTest.java` — 解析器单测。

**Backend (modify):**
- `src/main/java/cn/har01d/alist_tvbox/entity/Session.java` — 加 `loginIp`/`userAgent` 字段。
- `src/main/java/cn/har01d/alist_tvbox/auth/TokenService.java` — `encodeToken` 增加 ip/ua 参数。
- `src/main/java/cn/har01d/alist_tvbox/auth/SessionTokenService.java` — 写入新列 + UA 截断。
- `src/main/java/cn/har01d/alist_tvbox/service/UserService.java` — `generateToken`/`updateAccount` 透传 ip/ua；新增 `listSessions`/`revokeSession`。
- `src/main/java/cn/har01d/alist_tvbox/web/UserController.java` — 注入 `HttpServletRequest`；新增两个端点。
- `src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java` — 同步签名变更 + 新增会话逻辑测试。

**Frontend (modify):**
- `web-ui/src/services/account.service.ts` — 加 `getSessions`/`revokeSession`。
- `web-ui/src/views/UserView.vue` — 加会话表格 + 注销交互。

**Native image:** `src/main/resources/META-INF/native-image/reflect-config.json` — 由 `Main` 重新生成（`SessionDto` 已在扫描范围）。

---

## Task 1: UserAgentParser（TDD）

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/util/UserAgentParser.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/util/UserAgentParserTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/cn/har01d/alist_tvbox/util/UserAgentParserTest.java`:

```java
package cn.har01d.alist_tvbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAgentParserTest {
    @Test
    void parsesChromeOnWindows() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        assertEquals("Chrome 120", UserAgentParser.parseBrowser(ua));
        assertEquals("Windows", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesEdgeBeforeChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
        assertEquals("Edge 120", UserAgentParser.parseBrowser(ua));
    }

    @Test
    void parsesFirefoxOnMac() {
        String ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_1) Gecko/20100101 Firefox/121.0";
        assertEquals("Firefox 121", UserAgentParser.parseBrowser(ua));
        assertEquals("macOS", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesSafariOnIphone() {
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1";
        assertEquals("Safari 17", UserAgentParser.parseBrowser(ua));
        assertEquals("iOS", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesOperaAndroid() {
        String ua = "Mozilla/5.0 (Linux; Android 13) Chrome/120.0.0.0 Safari/537.36 OPR/105.0.0.0";
        assertEquals("Opera 105", UserAgentParser.parseBrowser(ua));
        assertEquals("Android", UserAgentParser.parseOs(ua));
    }

    @Test
    void parsesLinux() {
        String ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
        assertEquals("Linux", UserAgentParser.parseOs(ua));
    }

    @Test
    void unknownWhenNullOrBlank() {
        assertEquals("未知", UserAgentParser.parseBrowser(null));
        assertEquals("未知", UserAgentParser.parseBrowser(""));
        assertEquals("未知", UserAgentParser.parseBrowser("   "));
        assertEquals("未知", UserAgentParser.parseOs(null));
        assertEquals("未知", UserAgentParser.parseOs("curl/8.0"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=UserAgentParserTest`
Expected: FAIL — `UserAgentParser` 不存在（编译错误）。

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/cn/har01d/alist_tvbox/util/UserAgentParser.java`:

```java
package cn.har01d.alist_tvbox.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UserAgentParser {
    private static final Pattern EDGE = Pattern.compile("Edg[a-z]*/(\\d+)");
    private static final Pattern OPERA = Pattern.compile("OPR/(\\d+)");
    private static final Pattern FIREFOX = Pattern.compile("Firefox/(\\d+)");
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern SAFARI = Pattern.compile("Version/(\\d+).*Safari|(?:^|[^a-z])Safari/(\\d+)");

    private UserAgentParser() {
    }

    public static String parseBrowser(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        Matcher m = EDGE.matcher(ua);
        if (m.find()) return "Edge " + m.group(1);
        m = OPERA.matcher(ua);
        if (m.find()) return "Opera " + m.group(1);
        m = FIREFOX.matcher(ua);
        if (m.find()) return "Firefox " + m.group(1);
        m = CHROME.matcher(ua);
        if (m.find()) return "Chrome " + m.group(1);
        m = SAFARI.matcher(ua);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            return "Safari " + v;
        }
        return "未知";
    }

    public static String parseOs(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("windows") || lower.contains("win64") || lower.contains("win32")) {
            return "Windows";
        }
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) {
            return "iOS";
        }
        if (lower.contains("mac os x") || lower.contains("macintosh") || lower.contains("darwin")) {
            return "macOS";
        }
        if (lower.contains("android")) {
            return "Android";
        }
        if (lower.contains("linux") || lower.contains("x11") || lower.contains("unix")) {
            return "Linux";
        }
        return "未知";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=UserAgentParserTest`
Expected: PASS（7 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/util/UserAgentParser.java src/test/java/cn/har01d/alist_tvbox/util/UserAgentParserTest.java
git commit -m "feat: add UserAgentParser for browser/os detection"
```

---

## Task 2: Flyway 迁移 + Session 实体字段

**Files:**
- Create: `src/main/resources/db/migration/common/V6__add_session_login_info.sql`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Session.java`

- [ ] **Step 1: 写迁移脚本**

Create `src/main/resources/db/migration/common/V6__add_session_login_info.sql`:

```sql
ALTER TABLE session ADD COLUMN login_ip VARCHAR(45);
ALTER TABLE session ADD COLUMN user_agent VARCHAR(512);
```

（两条单列 `ADD COLUMN`，H2/MySQL/PostgreSQL 均合法；`common/` 目录跨三厂商生效，见 `application.yaml:32`。）

- [ ] **Step 2: 修改 Session 实体**

In `src/main/java/cn/har01d/alist_tvbox/entity/Session.java`, 在 `role` 字段之后、`expireTime` 之前插入：

```java
    @Column(name = "login_ip")
    private String loginIp;

    @Column(name = "user_agent")
    private String userAgent;
```

- [ ] **Step 3: 验证迁移与实体一致**

Run: `mvn test -Dtest=PostgreSqlMigrationTest,MySqlMigrationScriptTest`
Expected: PASS（现有迁移测试仍通过，V6 被加载无报错）。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/common/V6__add_session_login_info.sql src/main/java/cn/har01d/alist_tvbox/entity/Session.java
git commit -m "feat: add login_ip/user_agent columns to session table"
```

---

## Task 3: SessionDto

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/SessionDto.java`

- [ ] **Step 1: 创建 DTO**

Create `src/main/java/cn/har01d/alist_tvbox/dto/SessionDto.java`:

```java
package cn.har01d.alist_tvbox.dto;

import java.time.Instant;

import lombok.Data;

@Data
public class SessionDto {
    private Integer id;
    private String username;
    private String role;
    private String loginIp;
    private String userAgent;
    private String browser;
    private String os;
    private Instant loginTime;
    private Instant expireTime;
    private boolean current;
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/SessionDto.java
git commit -m "feat: add SessionDto for login session view"
```

---

## Task 4: 登录时捕获 IP/UA（透传 encodeToken）

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/auth/TokenService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/auth/SessionTokenService.java:42-56`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/UserService.java:181-185,252-281`
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/UserController.java:49-71`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java:95,114,117,144,147`

- [ ] **Step 1: 扩展 TokenService 接口**

In `src/main/java/cn/har01d/alist_tvbox/auth/TokenService.java`, 替换方法签名：

```java
package cn.har01d.alist_tvbox.auth;

public interface TokenService {
    UserToken extractToken(String rawToken);

    String encodeToken(int userId, String username, String authority, String loginIp, String userAgent);
}
```

- [ ] **Step 2: 实现新签名并写入新列**

In `src/main/java/cn/har01d/alist_tvbox/auth/SessionTokenService.java`, 替换 `encodeToken` 方法（第 42-56 行）：

```java
    @Override
    public String encodeToken(int userId, String username, String authority, String loginIp, String userAgent) {
        if (sessionRepository.countByUsername(username) >= 5) {
            var session = sessionRepository.findFirstByUsername(username);
            sessionRepository.delete(session);
        }
        var session = new Session();
        session.setToken(UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(userId);
        session.setUsername(username);
        session.setRole(authority);
        session.setLoginIp(loginIp);
        session.setUserAgent(truncate(userAgent, 512));
        session.setExpireTime(Instant.now().plus(30, ChronoUnit.DAYS));
        sessionRepository.save(session);
        return session.getToken();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
```

- [ ] **Step 3: UserService 透传 ip/ua**

In `src/main/java/cn/har01d/alist_tvbox/service/UserService.java`:

替换 `generateToken`（第 181-185 行）：

```java
    public UserToken generateToken(User user, String loginIp, String userAgent) {
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
        String token = tokenService.encodeToken(user.getId(), user.getUsername(), user.getRole().name(), loginIp, userAgent);
        return new UserToken(user.getId(), user.getUsername(), authorities, token);
    }
```

替换 `updateAccount` 签名（第 252 行）及其末尾 `generateToken` 调用（第 280 行）：

```java
    public UserToken updateAccount(UserDto dto, String loginIp, String userAgent) {
```
...（方法体不变，直到最后一行）...
```java
        return generateToken(user, loginIp, userAgent);
    }
```

- [ ] **Step 4: UserController 注入 HttpServletRequest**

In `src/main/java/cn/har01d/alist_tvbox/web/UserController.java`:

加 import：
```java
import jakarta.servlet.http.HttpServletRequest;
import cn.har01d.alist_tvbox.util.Utils;
```

替换 `login`（第 49-56 行）：
```java
    @PostMapping("/api/accounts/login")
    public UserToken login(@RequestBody LoginDto account, HttpServletRequest request) {
        User user = userService.findByUsername(account.getUsername());
        if (user == null || !passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
        return userService.generateToken(user, Utils.getClientIp(request), request.getHeader("User-Agent"));
    }
```

替换 `updateAccount`（第 68-71 行）：
```java
    @PostMapping("/api/accounts/update")
    public UserToken updateAccount(@RequestBody UserDto user, HttpServletRequest request) {
        return userService.updateAccount(user, Utils.getClientIp(request), request.getHeader("User-Agent"));
    }
```

- [ ] **Step 5: 同步更新 UserServiceTest**

In `src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java`:

第 95 行：`userService.updateAccount(dto)` → `userService.updateAccount(dto, null, null)`
第 114 行：`when(tokenService.encodeToken(1, "new-admin", "ADMIN"))` → `when(tokenService.encodeToken(1, "new-admin", "ADMIN", null, null))`
第 117 行：`userService.updateAccount(dto)` → `userService.updateAccount(dto, null, null)`
第 144 行：`when(tokenService.encodeToken(1, "admin", "ADMIN"))` → `when(tokenService.encodeToken(1, "admin", "ADMIN", null, null))`
第 147 行：`userService.updateAccount(dto)` → `userService.updateAccount(dto, null, null)`

- [ ] **Step 6: 运行测试验证**

Run: `mvn test -Dtest=UserServiceTest`
Expected: PASS（所有原有测试通过，签名变更已同步）。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/auth/TokenService.java src/main/java/cn/har01d/alist_tvbox/auth/SessionTokenService.java src/main/java/cn/har01d/alist_tvbox/service/UserService.java src/main/java/cn/har01d/alist_tvbox/web/UserController.java src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java
git commit -m "feat: capture login IP and User-Agent into session"
```

---

## Task 5: listSessions / revokeSession 服务方法（TDD）

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/UserService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java`

- [ ] **Step 1: 先写失败测试**

在 `src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java` 末尾（类内最后一个 `}` 之前）追加：

```java
    @Test
    void listSessionsMarksCurrentAndParsesDevice() {
        Session a = new Session();
        a.setId(10);
        a.setToken("token-a");
        a.setUsername("admin");
        a.setRole("ADMIN");
        a.setLoginIp("1.2.3.4");
        a.setUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36");
        Session b = new Session();
        b.setId(11);
        b.setToken("token-b");
        b.setUsername("admin");
        b.setRole("ADMIN");
        b.setLoginIp("9.9.9.9");
        b.setUserAgent(null);

        when(sessionRepository.findAllByUsername("admin")).thenReturn(List.of(a, b));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token-b"));

        var sessions = userService.listSessions("token-b");

        assertEquals(2, sessions.size());
        SessionDto current = sessions.stream().filter(SessionDto::isCurrent).findFirst().orElseThrow();
        assertEquals(11, current.getId());
        assertEquals("1.2.3.4", sessions.get(0).getLoginIp());
        assertEquals("Chrome 120", sessions.get(0).getBrowser());
        assertEquals("Windows", sessions.get(0).getOs());
        assertEquals("未知", sessions.get(1).getBrowser());
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokeSessionRejectsOtherUsersSession() {
        Session s = new Session();
        s.setId(20);
        s.setUsername("alice");
        when(sessionRepository.findById(20)).thenReturn(Optional.of(s));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));

        assertThrows(UserUnauthorizedException.class, () -> userService.revokeSession(20));
        verify(sessionRepository, never()).delete(any(Session.class));
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokeSessionDeletesOwnedSession() {
        Session s = new Session();
        s.setId(21);
        s.setUsername("admin");
        when(sessionRepository.findById(21)).thenReturn(Optional.of(s));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));
        userService.revokeSession(21);

        verify(sessionRepository).delete(s);
        SecurityContextHolder.clearContext();
    }
```

同时在该测试文件顶部加 import：
```java
import cn.har01d.alist_tvbox.dto.SessionDto;
import cn.har01d.alist_tvbox.entity.Session;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=UserServiceTest#listSessionsMarksCurrentAndParsesDevice+revokeSessionRejectsOtherUsersSession+revokeSessionDeletesOwnedSession`
Expected: FAIL — `listSessions`/`revokeSession` 方法不存在（编译错误）。

- [ ] **Step 3: 实现 listSessions / revokeSession**

在 `src/main/java/cn/har01d/alist_tvbox/service/UserService.java` 中：

加 import：
```java
import cn.har01d.alist_tvbox.dto.SessionDto;
import cn.har01d.alist_tvbox.entity.Session;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.util.UserAgentParser;
```

在 `revokeSession`/`listSessions` 尚不存在的情况下，于 `logout()` 方法之后插入：

```java
    public List<SessionDto> listSessions(String currentToken) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        return sessionRepository.findAllByUsername(username).stream()
                .map(s -> toDto(s, currentToken))
                .toList();
    }

    public void revokeSession(int id) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("会话不存在"));
        if (!username.equals(session.getUsername())) {
            throw new UserUnauthorizedException("无权操作该会话", 40301);
        }
        sessionRepository.delete(session);
    }

    private SessionDto toDto(Session s, String currentToken) {
        SessionDto dto = new SessionDto();
        dto.setId(s.getId());
        dto.setUsername(s.getUsername());
        dto.setRole(s.getRole());
        dto.setLoginIp(s.getLoginIp());
        dto.setUserAgent(s.getUserAgent());
        dto.setBrowser(UserAgentParser.parseBrowser(s.getUserAgent()));
        dto.setOs(UserAgentParser.parseOs(s.getUserAgent()));
        dto.setLoginTime(s.getCreateTime());
        dto.setExpireTime(s.getExpireTime());
        dto.setCurrent(currentToken != null && currentToken.equals(s.getToken()));
        return dto;
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=UserServiceTest`
Expected: PASS（含新增 3 个测试在内的全部测试通过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/UserService.java src/test/java/cn/har01d/alist_tvbox/service/UserServiceTest.java
git commit -m "feat: list and revoke login sessions for current user"
```

---

## Task 6: 会话端点

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/UserController.java`

- [ ] **Step 1: 新增 GET/DELETE 端点**

在 `src/main/java/cn/har01d/alist_tvbox/web/UserController.java` 中：

加 import（`SecurityContextHolder`、`GetMapping`、`DeleteMapping`、`PathVariable` 已存在于该文件，无需重复添加）：
```java
import cn.har01d.alist_tvbox.dto.SessionDto;
```

在 `updateAccount` 方法之后追加：

```java
    @GetMapping("/api/accounts/sessions")
    public List<SessionDto> sessions() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return userService.listSessions(token);
    }

    @DeleteMapping("/api/accounts/sessions/{id}")
    public void revokeSession(@PathVariable int id) {
        userService.revokeSession(id);
    }
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/UserController.java
git commit -m "feat: expose login sessions endpoints"
```

---

## Task 7: 前端 account.service

**Files:**
- Modify: `web-ui/src/services/account.service.ts`

- [ ] **Step 1: 加 getSessions / revokeSession**

在 `web-ui/src/services/account.service.ts` 的 `logout()` 方法之后、类结束 `}` 之前插入：

```ts
  getSessions() {
    return axios.get("/api/accounts/sessions").then(({data}) => data)
  }

  revokeSession(id: number) {
    return axios.delete(`/api/accounts/sessions/${id}`)
  }
```

- [ ] **Step 2: lint**

Run: `cd web-ui && npm run lint`
Expected: 无错误。

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/services/account.service.ts
git commit -m "feat(web-ui): add session api methods"
```

---

## Task 8: UserView 会话表格

**Files:**
- Modify: `web-ui/src/views/UserView.vue`

- [ ] **Step 1: 在模板末尾加会话区**

在 `web-ui/src/views/UserView.vue` 的 `</el-form>` 之后、`</template>` 之前插入：

```vue
  <el-divider/>
  <h3>登录会话</h3>
  <el-table :data="sessions" border style="width: 100%; min-width: 600px">
    <el-table-column label="登录时间" min-width="180">
      <template #default="{ row }">{{ formatTime(row.loginTime) }}</template>
    </el-table-column>
    <el-table-column label="设备" min-width="180">
      <template #default="{ row }">
        <el-tooltip v-if="row.userAgent" :content="row.userAgent" placement="top">
          <span>{{ row.browser }} · {{ row.os }}</span>
        </el-tooltip>
        <span v-else>{{ row.browser }} · {{ row.os }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="loginIp" label="IP" width="150"/>
    <el-table-column label="过期时间" min-width="180">
      <template #default="{ row }">{{ formatTime(row.expireTime) }}</template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="160">
      <template #default="{ row }">
        <el-tag v-if="row.current" type="success" size="small" style="margin-right: 8px">当前</el-tag>
        <el-button type="danger" size="small" @click="revoke(row)">注销</el-button>
      </template>
    </el-table-column>
  </el-table>
```

- [ ] **Step 2: 在 `<script setup>` 加会话逻辑**

现有文件第 23 行已 `import {reactive, ref} from "vue";` 和第 24 行 `import {ElMessage} from "element-plus";`。把第 23 行改为同时引入 `onMounted`：

```ts
import {reactive, ref, onMounted} from "vue";
```

在第 25 行 `import accountService from "@/services/account.service";` 之后追加 `ElMessageBox`：

```ts
import {ElMessage, ElMessageBox} from "element-plus";
```
（即把原第 24 行的 `import {ElMessage} from "element-plus";` 改为上面这行。）

然后在 `const form = ref({...})` 块之前插入类型、状态与函数：

```ts
interface SessionInfo {
  id: number
  username: string
  role: string
  loginIp: string
  userAgent: string
  browser: string
  os: string
  loginTime: string
  expireTime: string
  current: boolean
}

const sessions = ref<SessionInfo[]>([])

const formatTime = (t: string) => t ? new Date(t).toLocaleString() : '-'

const loadSessions = () => {
  accountService.getSessions().then((data) => {
    sessions.value = data
  })
}

const revoke = (row: SessionInfo) => {
  const tip = row.current ? '（这是当前会话，注销后将退出登录）' : ''
  ElMessageBox.confirm(`确定注销该会话？${tip}`, '提示', {type: 'warning'})
      .then(() => accountService.revokeSession(row.id))
      .then(() => {
        ElMessage.success('已注销')
        loadSessions()
      })
      .catch(() => {
      })
}

onMounted(loadSessions)
```

- [ ] **Step 3: build + lint**

Run: `cd web-ui && npm run build && npm run lint`
Expected: build 成功，lint 无错误。

- [ ] **Step 4: Commit**

```bash
git add web-ui/src/views/UserView.vue
git commit -m "feat(web-ui): show login sessions on user page"
```

---

## Task 9: GraalVM reflect-config 重生成 + 校验

**Files:**
- Regenerate: `src/main/resources/META-INF/native-image/reflect-config.json`（`SessionDto` 属 `dto/`，已被 `Main.java:33` 扫描）

- [ ] **Step 1: 重生成 reflect-config**

Run:
```bash
mvn compile
java -cp target/classes cn.har01d.alist_tvbox.Main
```
Expected: 程序输出 reflect-config 已写入；`git diff` 显示 `reflect-config.json` 新增 `SessionDto` 条目。

- [ ] **Step 2: 提交 reflect-config 变更（若有）**

```bash
git add src/main/resources/META-INF/native-image/reflect-config.json
git diff --cached --quiet || git commit -m "chore: regenerate reflect-config for SessionDto"
```

- [ ] **Step 3: 本地原生构建验证（CLAUDE.md 要求）**

Run:
```bash
mvn clean package -Pnative
./target/alist-tvbox
```
Expected: 原生二进制启动正常，无 `unsupported protocol: resource` 或反射错误（手动登录后访问 `/api/accounts/sessions` 返回 JSON）。

- [ ] **Step 4: 全量回归**

Run: `mvn test`
Expected: 全部测试通过。

---

## Self-Review 记录

- **Spec 覆盖**：会话展示（Task 5/6/8）、IP/UA 捕获（Task 4）、设备解析（Task 1）、注销（Task 5/6/8）、当前标记（Task 5）、Flyway（Task 2）、GraalVM（Task 9）— 全覆盖。
- **类型一致**：`Instant`（非 Timestamp）贯穿 `Session`/`SessionDto`/前端 ISO 字符串；`encodeToken(user, ip, ua)`/`generateToken`/`updateAccount`/`listSessions`/`revokeSession` 签名在各 Task 一致。
- **占位符**：无（Task 8 已显式删除占位行）。

## 未决问题

无。
