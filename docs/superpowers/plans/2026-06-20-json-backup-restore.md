# JSON-first 备份/恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 部署脚本 `scripts/alist-tvbox.sh` 支持 JSON 备份恢复（JSON 优先，SQL fallback），并统一菜单 b 备份为可恢复的 JSON 格式。

**Architecture:** 恢复纯脚本：文件名探测 `-json-` 区分类型，JSON→`database-json.zip`+重启（容器内 `StartupJsonRestoreRunner` OVERWRITE+exit 85，`--restart=always` 干净重启），SQL→`database.zip`+删 `atv.mv.db`+重启（`init.sh` RunScript）。备份需宿主触发容器：新增 permitAll 本地端点 `POST /api/local/backup`（token 文件守卫，仿 `/api/local/admin/password`），脚本写 token→`docker exec` curl→删 token。

**Tech Stack:** Bash（脚本，`set -euo pipefail`，`ALIST_TVBOX_SOURCE_ONLY=1` 可 source）；Java 21 / Spring Boot 3 / Mockito（后端测试）。

**Spec:** `docs/superpowers/specs/2026-06-20-json-backup-restore-design.md`

---

## 文件结构

- 修改 `scripts/alist-tvbox.sh`：新增 `detect_backup_type` / `restore_json_backup` / `restore_sql_backup` / `generate_backup_token` / `write_backup_token` / `call_backup_api`；重写 `restore_database` 与 `backup_database_now`。
- 修改 `src/main/java/cn/har01d/alist_tvbox/web/UserController.java`：新增 `POST /api/local/backup`（token 守卫）+ `SettingService` 依赖 + `BACKUP_TOKEN_FILE` 常量。
- 修改 `src/main/java/cn/har01d/alist_tvbox/config/WebSecurityConfiguration.java`：permitAll 加 `/api/local/backup`。
- 新增测试 `src/test/java/cn/har01d/alist_tvbox/web/UserControllerLocalBackupTest.java`。
- 新增脚本单测 `scripts/test_backup_helpers.sh`。

---

## Task 1: 脚本 — `detect_backup_type` helper + 单测

**Files:**
- Modify: `scripts/alist-tvbox.sh`（加在 `restore_database()` 上方，约 line 1737）
- Create: `scripts/test_backup_helpers.sh`

- [ ] **Step 1: 写失败测试**

`scripts/test_backup_helpers.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
ALIST_TVBOX_SOURCE_ONLY=1 source "$(dirname "$0")/alist-tvbox.sh"
fail=0
check() { [[ "$1" == "$2" ]] || { echo "FAIL: $(basename "$3") expected $2 got $1"; fail=1; }; }
check "$(detect_backup_type "/x/database-json-2026-06-19.zip")" "JSON" "json"
check "$(detect_backup_type "/x/database-2026-06-20-091200.zip")" "SQL" "sql"
check "$(detect_backup_type "/x/database-json-old.zip")" "JSON" "json2"
check "$(detect_backup_type "/x/foo.zip")" "SQL" "foo"
[[ $fail -eq 0 ]] && { echo "PASS"; exit 0; } || exit 1
```

- [ ] **Step 2: 运行确认失败**

Run: `bash scripts/test_backup_helpers.sh`
Expected: FAIL（`detect_backup_type: command not found`）

- [ ] **Step 3: 实现 helper**

在 `scripts/alist-tvbox.sh` 的 `# 数据库恢复` 注释上方插入：
```bash
# 探测备份类型：文件名含 -json- 为 JSON 备份，否则视为 SQL 备份
detect_backup_type() {
  local name
  name="$(basename "$1")"
  if [[ "$name" == *"-json-"* ]]; then
    echo "JSON"
  else
    echo "SQL"
  fi
}
```

- [ ] **Step 4: 运行确认通过**

Run: `bash scripts/test_backup_helpers.sh` → Expected: `PASS`
Run: `bash -n scripts/alist-tvbox.sh` → Expected: 无输出

- [ ] **Step 5: 提交**

```bash
git add scripts/alist-tvbox.sh scripts/test_backup_helpers.sh
git commit -m "feat(script): add detect_backup_type helper for SQL/JSON backup detection"
```

---

## Task 2: 脚本 — `restore_sql_backup` / `restore_json_backup` helpers

**Files:**
- Modify: `scripts/alist-tvbox.sh`（在 `detect_backup_type` 下方）

- [ ] **Step 1: 抽取 SQL 恢复 helper（沿用现有 `restore_database` body line 1799-1828 的逻辑）**

```bash
# SQL 备份恢复：复制到 database.zip，删除 mv.db，重启容器（init.sh 执行 RunScript）
restore_sql_backup() {
  local selected_backup="$1"
  local backup_name
  backup_name="$(basename "$selected_backup")"
  echo -e "${RED}警告: 将以 SQL 方式覆盖当前数据库!${NC}"
  read -p "确认恢复 ${backup_name}? [y/N] " confirm
  case "$confirm" in
    [Yy]*)
      echo -e "${CYAN}正在恢复数据库 (SQL)...${NC}"
      if ! cp "$selected_backup" "${CONFIG[BASE_DIR]}/database.zip" 2>/dev/null; then
        echo -e "${RED}复制备份文件失败 (权限不足)${NC}"
        return 1
      fi
      echo -e "${GREEN}✓ 备份文件已复制${NC}"
      rm -f "${CONFIG[BASE_DIR]}/atv.mv.db" 2>/dev/null && echo -e "${GREEN}✓ 已删除 atv.mv.db${NC}"
      rm -f "${CONFIG[BASE_DIR]}/atv.trace.db" 2>/dev/null && echo -e "${GREEN}✓ 已删除 atv.trace.db${NC}"
      local container_name
      container_name="$(get_container_name)"
      if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${YELLOW}正在重启容器...${NC}"
        docker restart "$container_name" >/dev/null 2>&1 && echo -e "${GREEN}✓ 容器已重启${NC}" || echo -e "${RED}容器重启失败${NC}"
      else
        echo -e "${YELLOW}容器不存在，请通过菜单 '1. 安装/更新' 启动容器${NC}"
      fi
      ;;
    *)
      echo -e "${YELLOW}已取消恢复${NC}"
      ;;
  esac
}
```

- [ ] **Step 2: 新增 JSON 恢复 helper**

```bash
# JSON 备份恢复：复制到 database-json.zip，重启容器（StartupJsonRestoreRunner OVERWRITE + exit 85 干净重启）
# 不删除 atv.mv.db：JSON 恢复通过 JPA 原地覆盖，init.sh 检测到 database-json.zip 时会跳过 SQL 恢复
restore_json_backup() {
  local selected_backup="$1"
  local backup_name
  backup_name="$(basename "$selected_backup")"
  echo -e "${RED}警告: 将以 JSON 方式覆盖恢复当前数据库 (OVERWRITE)!${NC}"
  read -p "确认恢复 ${backup_name}? [y/N] " confirm
  case "$confirm" in
    [Yy]*)
      echo -e "${CYAN}正在恢复数据库 (JSON)...${NC}"
      if ! cp "$selected_backup" "${CONFIG[BASE_DIR]}/database-json.zip" 2>/dev/null; then
        echo -e "${RED}复制备份文件失败 (权限不足)${NC}"
        return 1
      fi
      echo -e "${GREEN}✓ 备份文件已复制${NC}"
      local container_name
      container_name="$(get_container_name)"
      if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${YELLOW}正在重启容器...${NC}"
        docker restart "$container_name" >/dev/null 2>&1 && echo -e "${GREEN}✓ 容器已重启${NC}" || echo -e "${RED}容器重启失败${NC}"
        echo -e "\n${GREEN}JSON 数据库恢复已触发!${NC}"
        echo -e "${YELLOW}容器将恢复后自动重启一次以加载恢复数据，请稍候${NC}"
      else
        echo -e "${YELLOW}容器不存在，请通过菜单 '1. 安装/更新' 启动容器${NC}"
      fi
      ;;
    *)
      echo -e "${YELLOW}已取消恢复${NC}"
      ;;
  esac
}
```

- [ ] **Step 3: 验证语法**

Run: `bash -n scripts/alist-tvbox.sh` → Expected: 无输出
Run: `bash scripts/test_backup_helpers.sh` → Expected: `PASS`（确认未破坏既有）

- [ ] **Step 4: 提交**

```bash
git add scripts/alist-tvbox.sh
git commit -m "feat(script): add restore_sql_backup / restore_json_backup helpers"
```

---

## Task 3: 脚本 — 重写 `restore_database` 列单+标注+派发（JSON 优先）

**Files:**
- Modify: `scripts/alist-tvbox.sh`（替换 `restore_database()` body line 1738-1836 的列单+选择+恢复部分）

- [ ] **Step 1: 替换 `restore_database()`**

保留函数开头的备份目录检查与列单（line 1739-1775 的 `find ... -name "*.zip"`），把后续「显示+选择+恢复」改为按类型标注、JSON 排前、派发。完整替换 `restore_database()` 为：

```bash
restore_database() {
  local backup_dir="${CONFIG[BASE_DIR]}/backup"

  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          数据库恢复 (JSON 优先)          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  if [[ ! -d "$backup_dir" ]]; then
    echo -e "${YELLOW}备份目录不存在: $backup_dir${NC}"
    echo -e "${YELLOW}提示: 系统每天自动备份 (SQL 06:00 / JSON 06:30) 到此目录${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  local backups=()
  while IFS= read -r file; do
    backups+=("$file")
  done < <(find "$backup_dir" -maxdepth 1 -type f -name "*.zip" -printf "%T@ %p\n" 2>/dev/null | sort -rn | cut -d' ' -f2-)

  if [[ ${#backups[@]} -eq 0 ]]; then
    echo -e "${YELLOW}未找到备份文件${NC}"
    echo -e "${YELLOW}提示: 备份文件为 .zip，保存在 $backup_dir${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  # 标注类型；JSON 排前（优先）
  local types=()
  local json_idx=() sql_idx=()
  for i in "${!backups[@]}"; do
    local t
    t="$(detect_backup_type "${backups[$i]}")"
    types[$i]="$t"
    if [[ "$t" == "JSON" ]]; then json_idx+=("$i"); else sql_idx+=("$i"); fi
  done
  local order=("${json_idx[@]}" "${sql_idx[@]}")

  echo -e "${YELLOW}可用的备份文件 (JSON 优先):${NC}\n"
  local n=0
  for i in "${order[@]}"; do
    n=$((n+1))
    local file="${backups[$i]}"
    local filename
    filename="$(basename "$file")"
    local filesize
    filesize="$(ls -lh "$file" | awk '{print $5}')"
    local filetime
    filetime="$(ls -l --time-style='+%Y-%m-%d %H:%M:%S' "$file" | awk '{print $6, $7}')"
    local label="${types[$i]}"
    echo -e " $n. [${label}] ${GREEN}${filename}${NC}"
    echo -e "    大小: ${filesize}  时间: ${filetime}"
    echo ""
  done

  echo -e " 0. 取消"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择要恢复的备份 [0-${#order[@]}]: " choice

  if [[ "$choice" == "0" ]]; then return; fi
  if ! [[ "$choice" =~ ^[0-9]+$ ]] || [[ "$choice" -lt 1 ]] || [[ "$choice" -gt ${#order[@]} ]]; then
    echo -e "${RED}无效选择!${NC}"
    sleep 2
    return
  fi

  local sel="${order[$((choice-1))]}"
  local selected_backup="${backups[$sel]}"
  local kind="${types[$sel]}"

  if [[ "$kind" == "JSON" ]]; then
    restore_json_backup "$selected_backup"
  else
    restore_sql_backup "$selected_backup"
  fi

  read -n 1 -s -r -p "按任意键继续..."
}
```

- [ ] **Step 2: 验证语法 + 单测**

Run: `bash -n scripts/alist-tvbox.sh` → Expected: 无输出
Run: `bash scripts/test_backup_helpers.sh` → Expected: `PASS`

- [ ] **Step 3: 提交**

```bash
git add scripts/alist-tvbox.sh
git commit -m "feat(script): restore_database lists both SQL/JSON, JSON-first dispatch"
```

---

## Task 4: 后端 — permitAll `/api/local/backup` + UserController token 守卫

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/config/WebSecurityConfiguration.java:36`（permitAll 列表）
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/UserController.java`（构造器注入 `SettingService` + 新端点）

- [ ] **Step 1: permitAll 放行 `/api/local/backup`**

`WebSecurityConfiguration.java` 的 permitAll `requestMatchers(...)` 列表中，在 `"/api/local/admin/password",` 下一行加：
```java
                                "/api/local/backup",
```

- [ ] **Step 2: UserController 注入 SettingService**

构造器改为：
```java
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SettingService settingService;
    private static final String ADMIN_RESET_TOKEN_FILE = "admin_reset_token";
    private static final String BACKUP_TOKEN_FILE = "backup_token";

    public UserController(UserService userService, PasswordEncoder passwordEncoder, SettingService settingService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.settingService = settingService;
    }
```
（保留原字段/常量，仅新增 `settingService` 字段、`BACKUP_TOKEN_FILE` 常量、构造器第 3 参。补 `import cn.har01d.alist_tvbox.service.SettingService;` 及 `java.io.File` / `java.nio.file.Files` / `java.nio.file.Path` / `java.nio.charset.StandardCharsets` / `cn.har01d.alist_tvbox.util.Utils` 视已有 import 补齐。）

- [ ] **Step 3: 新增 `/api/local/backup` 端点**

在 `resetAdminPassword` 方法后追加：
```java
    @PostMapping("/api/local/backup")
    public Map<String, String> backupLocal(HttpServletRequest request,
                                           @RequestParam(name = "type", defaultValue = "json") String type) {
        String requestToken = request.getHeader("X-BACKUP-TOKEN");
        Path tokenFile = Utils.getDataPath("atv", BACKUP_TOKEN_FILE);
        boolean authorized = false;
        try {
            if (requestToken == null || requestToken.isBlank() || !Files.exists(tokenFile)) {
                throw new BadRequestException("备份令牌无效");
            }
            String expected = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!requestToken.equals(expected)) {
                throw new BadRequestException("备份令牌无效");
            }
            authorized = true;
            String filename;
            if ("sql".equalsIgnoreCase(type)) {
                File out = settingService.backupDatabase();
                if (out == null) {
                    throw new BadRequestException("SQL 备份不可用（非 H2 数据库）");
                }
                filename = out.getName();
            } else {
                settingService.backupJsonDatabase();
                filename = "database-json-" + LocalDate.now() + ".zip";
            }
            return Map.of("file", filename, "path", Utils.getDataPath("backup", filename).toString());
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("备份失败", e);
        } finally {
            if (authorized) {
                try {
                    Files.deleteIfExists(tokenFile);
                } catch (Exception ignored) {
                }
            }
        }
    }
```
（补 import：`java.time.LocalDate`、`cn.har01d.alist_tvbox.exception.BadRequestException` 视已有补齐。）

- [ ] **Step 4: 编译**

Run: `mvn -q compile` → Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/config/WebSecurityConfiguration.java src/main/java/cn/har01d/alist_tvbox/web/UserController.java
git commit -m "feat(backend): add token-guarded /api/local/backup endpoint (JSON primary, SQL fallback)"
```

---

## Task 5: 后端 — `/api/local/backup` token 守卫单测

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/web/UserControllerLocalBackupTest.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/web/UserControllerAdminPasswordResetTest.java`（构造器多一个 `SettingService` mock 参）

- [ ] **Step 1: 写失败测试**

`UserControllerLocalBackupTest.java`（仿 `UserControllerAdminPasswordResetTest`）：
```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserControllerLocalBackupTest {
    @TempDir Path dataDir;
    @Mock PasswordEncoder passwordEncoder;
    @Mock cn.har01d.alist_tvbox.service.UserService userService;
    @Mock SettingService settingService;
    private UserController controller;

    @BeforeEach void setUp() {
        System.setProperty("atv.data.dir", dataDir.toString());
        controller = new UserController(userService, passwordEncoder, settingService);
    }
    @AfterEach void tearDown() { System.clearProperty("atv.data.dir"); }

    @Test void jsonBackupSucceedsWithValidToken() throws Exception {
        doNothing().when(settingService).backupJsonDatabase();
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-BACKUP-TOKEN", "tok");
        Map<String, String> res = controller.backupLocal(req, "json");
        assertTrue(res.get("file").startsWith("database-json-"));
        verify(settingService).backupJsonDatabase();
    }

    @Test void rejectsMissingToken() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThrows(BadRequestException.class, () -> controller.backupLocal(req, "json"));
    }

    @Test void rejectsWrongToken() throws Exception {
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("backup_token"), "tok");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-BACKUP-TOKEN", "wrong");
        assertThrows(BadRequestException.class, () -> controller.backupLocal(req, "json"));
    }
}
```

修复既有测试 `UserControllerAdminPasswordResetTest.setUp()` 的构造调用：
```java
        userController = new UserController(userService, passwordEncoder,
            org.mockito.Mockito.mock(cn.har01d.alist_tvbox.service.SettingService.class));
```

- [ ] **Step 2: 运行确认通过（含既有 admin reset 测试）**

Run: `mvn -q test -Dtest=UserControllerLocalBackupTest,UserControllerAdminPasswordResetTest` → Expected: Tests run: 4+, Failures: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/web/UserControllerLocalBackupTest.java src/test/java/cn/har01d/alist_tvbox/web/UserControllerAdminPasswordResetTest.java
git commit -m "test(backend): cover /api/local/backup token guard"
```

---

## Task 6: 脚本 — 重写 `backup_database_now`（JSON 优先，SQL/atv.mv.db fallback）

**Files:**
- Modify: `scripts/alist-tvbox.sh`（替换 `backup_database_now()` line 1410-1455，加 3 个 helper）

- [ ] **Step 1: 新增 token/api helpers（放在 `backup_database_now` 上方）**

```bash
generate_backup_token() {
  local token=""
  while [[ ${#token} -lt 32 ]]; do
    token="${token}$(dd if=/dev/urandom bs=24 count=1 2>/dev/null | base64 | tr -dc 'A-Za-z0-9')"
  done
  printf '%s' "${token:0:32}"
}

write_backup_token() {
  local container_name="$1" token="$2"
  docker exec "$container_name" sh -lc "mkdir -p /data/atv && umask 077 && printf '%s' '$token' > /data/atv/backup_token"
}

# 调用容器内本地备份端点；$3=type(json|sql)。成功打印产物文件名，失败返回非 0
call_backup_api() {
  local container_name="$1" token="$2" type="$3"
  docker exec "$container_name" sh -lc "curl -fsS -X POST -H 'X-BACKUP-TOKEN: $token' 'http://127.0.0.1:4567/api/local/backup?type=$type'" \
    | tr -d '\n' | sed -n 's/.*"file"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}
```

- [ ] **Step 2: 重写 `backup_database_now()`**

```bash
backup_database_now() {
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          立即备份数据库 (JSON 优先)          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  local container_name
  container_name="$(get_container_name)"
  local status
  status="$(check_container_status)"

  mkdir -p "${CONFIG[BASE_DIR]}/backup" 2>/dev/null || {
    echo -e "${RED}无法创建备份目录 (权限不足)${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  }

  # 容器未运行 → 退回裸 atv.mv.db 复制（兜底，与历史行为一致）
  if [[ "$status" != "running" ]]; then
    echo -e "${YELLOW}容器未运行，退回裸文件备份 atv.mv.db${NC}"
    local db_file="${CONFIG[BASE_DIR]}/atv.mv.db"
    if [[ ! -f "$db_file" ]]; then
      echo -e "${RED}数据库文件不存在: $db_file${NC}"
      read -n 1 -s -r -p "按任意键继续..."
      return
    fi
    local ts
    ts="$(date +"%Y-%m-%d-%H%M%S")"
    local out="${CONFIG[BASE_DIR]}/backup/database-${ts}.zip"
    if command -v zip >/dev/null 2>&1 && (cd "${CONFIG[BASE_DIR]}" && zip -q "$out" atv.mv.db 2>/dev/null); then
      echo -e "${GREEN}✓ 备份成功 (atv.mv.db): ${out}${NC}"
    else
      echo -e "${RED}备份失败（未安装 zip 或权限不足）${NC}"
    fi
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  # 主：JSON 备份
  local token file
  token="$(generate_backup_token)"
  write_backup_token "$container_name" "$token"
  echo -e "${CYAN}正在生成 JSON 备份...${NC}"
  if file="$(call_backup_api "$container_name" "$token" "json" 2>/dev/null)" && [[ -n "$file" ]]; then
    local path="${CONFIG[BASE_DIR]}/backup/$file"
    local size
    size="$(ls -lh "$path" 2>/dev/null | awk '{print $5}')"
    echo -e "${GREEN}✓ JSON 备份成功${NC}"
    echo -e "备份文件: ${GREEN}${path}${NC}  大小: ${size}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  # fallback：SQL 备份（仅 H2）
  echo -e "${YELLOW}JSON 备份失败，尝试 SQL 备份...${NC}"
  token="$(generate_backup_token)"
  write_backup_token "$container_name" "$token"
  if file="$(call_backup_api "$container_name" "$token" "sql" 2>/dev/null)" && [[ -n "$file" ]]; then
    echo -e "${GREEN}✓ SQL 备份成功: ${CONFIG[BASE_DIR]}/backup/$file${NC}"
  else
    echo -e "${RED}备份失败：JSON 与 SQL 均失败，请查看容器日志: docker logs $container_name${NC}"
  fi
  read -n 1 -s -r -p "按任意键继续..."
}
```

- [ ] **Step 3: 验证**

Run: `bash -n scripts/alist-tvbox.sh` → Expected: 无输出
Run: `bash scripts/test_backup_helpers.sh` → Expected: `PASS`

- [ ] **Step 4: 提交**

```bash
git add scripts/alist-tvbox.sh
git commit -m "feat(script): backup_database_now uses JSON via /api/local/backup, SQL/raw fallback"
```

---

## Task 7: 全量验证

- [ ] **Step 1: 脚本**

Run: `bash -n scripts/alist-tvbox.sh` → 无输出
Run: `bash scripts/test_backup_helpers.sh` → `PASS`

- [ ] **Step 2: 后端**

Run: `mvn -q test -Dtest=UserControllerLocalBackupTest,UserControllerAdminPasswordResetTest` → 0 failures
Run: `mvn -q compile` → BUILD SUCCESS

- [ ] **Step 3: 手动冒烟（需运行中的容器，人工）**

- 菜单 8→b：确认产出 `${BASE_DIR}/backup/database-json-<date>.zip`，`unzip -l` 含 `manifest.json`。
- 菜单 8→a：列表中该文件标注 `[JSON]` 且排在前；选择后 `ls ${BASE_DIR}/database-json.zip` 存在；容器重启后日志出现 `Startup JSON restore completed` 与 exit 85 重启。
- SQL fallback：选一个 `database-<date>.zip`（非 `-json-`）恢复，确认走 `database.zip` + 删 mv.db 路径。

- [ ] **Step 4: 文档/收尾**

更新 `RELEASE_NOTES.md`（一行）：脚本支持 JSON 备份恢复（JSON 优先，SQL fallback）。
```bash
git add RELEASE_NOTES.md
git commit -m "docs: release notes for JSON-first backup/restore in deploy script"
```

---

## 自检（Self-Review）

- **Spec 覆盖**：恢复（Task 1-3）、备份统一（Task 4-6）、验证（Task 7）— 全覆盖。
- **类型/签名一致**：`detect_backup_type`→`JSON`/`SQL`（Task1）与 `restore_database` 判断（Task3）、`backup_database_now`（Task6）一致；`backupLocal(...)` 签名（Task4）与测试（Task5）一致；`new UserController(userService, passwordEncoder, settingService)` 三处（Task4/Task5 + 修既有测试）一致。
- **占位符**：无 TBD/TODO；所有代码块完整。
- **注意**：`/api/local/backup` 必须加入 permitAll（Task4 Step1），否则脚本无 token 时被 `/api/**` 的 ADMIN/CLIENT 拦截（脚本通过 token 文件守卫而非 API key）。
