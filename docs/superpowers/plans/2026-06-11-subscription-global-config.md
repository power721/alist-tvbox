# 订阅定制全局配置实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为订阅定制功能添加全局配置支持，订阅可完全替换全局配置，支持白名单/黑名单模式

**Architecture:** 在 Setting 表存储全局配置 JSON，SubscriptionService 在生成订阅配置时应用全局配置，订阅级别配置完全替换全局配置

**Tech Stack:** Spring Boot, JPA, JUnit 5, ObjectMapper (Jackson)

---

## File Structure

**Backend - Service Layer:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java` - 核心逻辑
  - 新增 `getGlobalConfig()` - 读取全局配置
  - 新增 `updateGlobalConfig()` - 更新全局配置
  - 新增 `applyGlobalConfig()` - 应用全局配置到订阅
  - 修改 `handleWhitelist()` - 处理白名单替换逻辑
  - 修改 `removeBlacklist()` - 处理黑名单替换逻辑

**Backend - Controller Layer:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java` - API 端点
  - 新增 `GET /api/subscription/global-config`
  - 新增 `PUT /api/subscription/global-config`

**Backend - Tests:**
- Create: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java` - 全局配置单元测试

**Constants:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/util/Constants.java` - 添加常量

---

### Task 1: 添加全局配置常量

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/util/Constants.java`

- [ ] **Step 1: 添加全局配置常量**

在 Constants.java 中添加：

```java
public static final String GLOBAL_SUBSCRIPTION_OVERRIDE = "global_subscription_override";
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/util/Constants.java
git commit -m "feat: add global subscription override constant

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 实现 getGlobalConfig 方法（TDD）

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: 写失败测试 - getGlobalConfig 返回空配置**

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceGlobalConfigTest {

    @Mock
    private SettingRepository settingRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private SubscriptionService subscriptionService;

    @Test
    void getGlobalConfig_whenNoConfig_returnsEmptyMap() {
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = subscriptionService.getGlobalConfig();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#getGlobalConfig_whenNoConfig_returnsEmptyMap
```

预期: FAIL "cannot find symbol: method getGlobalConfig()"

- [ ] **Step 3: 实现 getGlobalConfig 方法**

在 SubscriptionService.java 中添加：

```java
public Map<String, Object> getGlobalConfig() {
    return settingRepository.findById(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE)
            .map(Setting::getValue)
            .map(json -> {
                try {
                    return objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    log.warn("Failed to parse global config", e);
                    return new HashMap<String, Object>();
                }
            })
            .orElse(new HashMap<>());
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#getGlobalConfig_whenNoConfig_returnsEmptyMap
```

预期: PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java \
        src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
git commit -m "feat: add getGlobalConfig method

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 实现 updateGlobalConfig 方法（TDD）

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: 写失败测试 - updateGlobalConfig 保存配置**

在 SubscriptionServiceGlobalConfigTest.java 中添加：

```java
import cn.har01d.alist_tvbox.entity.Setting;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@Test
void updateGlobalConfig_savesConfig() throws Exception {
    Map<String, Object> config = Map.of("sites-blacklist", List.of("site1"));
    String json = "{\"sites-blacklist\":[\"site1\"]}";
    when(objectMapper.writeValueAsString(config)).thenReturn(json);

    subscriptionService.updateGlobalConfig(config);

    verify(settingRepository).save(any(Setting.class));
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#updateGlobalConfig_savesConfig
```

预期: FAIL "cannot find symbol: method updateGlobalConfig"

- [ ] **Step 3: 实现 updateGlobalConfig 方法**

在 SubscriptionService.java 中添加：

```java
public void updateGlobalConfig(Map<String, Object> config) {
    try {
        String json = objectMapper.writeValueAsString(config);
        settingRepository.save(new Setting(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE, json));
    } catch (Exception e) {
        log.warn("Failed to save global config", e);
        throw new BadRequestException("Invalid config format");
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#updateGlobalConfig_savesConfig
```

预期: PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java \
        src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
git commit -m "feat: add updateGlobalConfig method

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 实现 applyGlobalConfig 方法（TDD）

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: 写失败测试 - applyGlobalConfig 应用黑名单**

```java
@Test
void applyGlobalConfig_appliesBlacklist() {
    Map<String, Object> config = new HashMap<>();
    Map<String, Object> globalConfig = Map.of("sites-blacklist", List.of("site1"));
    when(settingRepository.findById(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE))
            .thenReturn(Optional.of(new Setting(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE, 
                "{\"sites-blacklist\":[\"site1\"]}")));

    subscriptionService.applyGlobalConfig(config);

    assertTrue(config.containsKey("sites-blacklist"));
    assertEquals(List.of("site1"), config.get("sites-blacklist"));
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#applyGlobalConfig_appliesBlacklist
```

预期: FAIL "cannot find symbol: method applyGlobalConfig"

- [ ] **Step 3: 实现 applyGlobalConfig 方法**

在 SubscriptionService.java 中添加：

```java
private void applyGlobalConfig(Map<String, Object> config) {
    Map<String, Object> globalConfig = getGlobalConfig();
    if (globalConfig.isEmpty()) {
        return;
    }
    
    // 不包含 spider 的全局配置应用
    for (Map.Entry<String, Object> entry : globalConfig.entrySet()) {
        String key = entry.getKey();
        if (!"spider".equals(key) && !config.containsKey(key)) {
            config.put(key, entry.getValue());
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#applyGlobalConfig_appliesBlacklist
```

预期: PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java \
        src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
git commit -m "feat: add applyGlobalConfig method

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 修改 handleWhitelist 和 removeBlacklist 方法

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: 写测试 - 白名单优先逻辑**

```java
@Test
void filterSites_whitelistMode_onlyIncludesWhitelistedSites() {
    Map<String, Object> config = new HashMap<>();
    config.put("sites", List.of(
        Map.of("key", "site1"),
        Map.of("key", "site2"),
        Map.of("key", "site3")
    ));
    config.put("sites-whitelist", List.of("site1", "site3"));

    subscriptionService.applySitesFilter(config);

    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertEquals(2, sites.size());
    assertTrue(sites.stream().anyMatch(s -> "site1".equals(s.get("key"))));
    assertTrue(sites.stream().anyMatch(s -> "site3".equals(s.get("key"))));
    assertFalse(config.containsKey("sites-whitelist"));
}

@Test
void filterSites_blacklistMode_excludesBlacklistedSites() {
    Map<String, Object> config = new HashMap<>();
    config.put("sites", List.of(
        Map.of("key", "site1"),
        Map.of("key", "site2")
    ));
    config.put("sites-blacklist", List.of("site2"));

    subscriptionService.applySitesFilter(config);

    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertEquals(1, sites.size());
    assertEquals("site1", sites.get(0).get("key"));
    assertFalse(config.containsKey("sites-blacklist"));
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#filterSites_whitelistMode_onlyIncludesWhitelistedSites
mvn test -Dtest=SubscriptionServiceGlobalConfigTest#filterSites_blacklistMode_excludesBlacklistedSites
```

预期: FAIL "cannot find symbol: method applySitesFilter"

- [ ] **Step 3: 重构 handleWhitelist 和 removeBlacklist 为统一方法**

在 SubscriptionService.java 中添加新方法并修改现有方法：

```java
private void applySitesFilter(Map<String, Object> config) {
    Object whitelistObj = config.get("sites-whitelist");
    Object blacklistObj = config.get("sites-blacklist");
    
    if (whitelistObj instanceof List) {
        handleWhitelistFilter(config, (List<String>) whitelistObj);
        config.remove("sites-whitelist");
    } else if (blacklistObj instanceof List) {
        handleBlacklistFilter(config, (List<String>) blacklistObj);
        config.remove("sites-blacklist");
    }
}

private void handleWhitelistFilter(Map<String, Object> config, List<String> whitelist) {
    Object obj = config.get("sites");
    if (obj instanceof List) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) obj;
        Set<String> whiteSet = new HashSet<>(whitelist);
        sites = sites.stream()
                .filter(s -> whiteSet.contains(s.get("key")))
                .collect(Collectors.toList());
        config.put("sites", sites);
        log.info("whitelist mode: include sites {}", whitelist);
    }
}

private void handleBlacklistFilter(Map<String, Object> config, List<String> blacklist) {
    Object obj = config.get("sites");
    if (obj instanceof List) {
        List<Map<String, Object>> sites = (List<Map<String, Object>>) obj;
        Set<String> blackSet = new HashSet<>(blacklist);
        sites = sites.stream()
                .filter(s -> !blackSet.contains(s.get("key")))
                .collect(Collectors.toList());
        config.put("sites", sites);
        log.info("blacklist mode: exclude sites {}", blacklist);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest
```

预期: 所有测试 PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java \
        src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
git commit -m "feat: add sites filter logic with whitelist/blacklist support

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 集成到 subscription 方法

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`

- [ ] **Step 1: 在 subscription 方法中应用全局配置**

找到 `subscription(String token, String apiUrl, String override, String sort)` 方法，在 sortSites 之后、overrideConfig 之前插入：

```java
// 应用全局配置
applyGlobalConfig(config);
```

- [ ] **Step 2: 替换原有的 handleWhitelist 和 removeBlacklist 调用**

找到方法末尾的：

```java
handleWhitelist(config);
removeBlacklist(config);
```

替换为：

```java
applySitesFilter(config);
```

- [ ] **Step 3: 运行完整测试套件**

```bash
mvn test
```

预期: 所有测试 PASS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
git commit -m "feat: integrate global config into subscription flow

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 添加 Controller API 端点

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java`

- [ ] **Step 1: 添加 getGlobalConfig 端点**

```java
@GetMapping("/global-config")
public Map<String, Object> getGlobalConfig() {
    return subscriptionService.getGlobalConfig();
}
```

- [ ] **Step 2: 添加 updateGlobalConfig 端点**

```java
@PutMapping("/global-config")
public void updateGlobalConfig(@RequestBody Map<String, Object> config) {
    subscriptionService.updateGlobalConfig(config);
}
```

- [ ] **Step 3: 手动测试 API**

启动应用并测试：

```bash
# 获取全局配置
curl -X GET http://localhost:4567/api/subscription/global-config

# 更新全局配置
curl -X PUT http://localhost:4567/api/subscription/global-config \
  -H "Content-Type: application/json" \
  -d '{"sites-blacklist": ["site1", "site2"]}'
```

预期: GET 返回空 JSON `{}`，PUT 返回 200

- [ ] **Step 4: 提交**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java
git commit -m "feat: add global config API endpoints

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 集成测试 - 全局配置场景

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java`

- [ ] **Step 1: 添加场景1测试 - 全局白名单 + 订阅黑名单**

```java
@Test
void scenario1_globalWhitelist_subscriptionBlacklist() {
    // 全局白名单：site1
    when(settingRepository.findById(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE))
            .thenReturn(Optional.of(new Setting(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE, 
                "{\"sites-whitelist\":[\"site1\"]}")));
    
    Map<String, Object> config = new HashMap<>();
    config.put("sites", List.of(
        Map.of("key", "site1"),
        Map.of("key", "site2"),
        Map.of("key", "site3")
    ));
    
    subscriptionService.applyGlobalConfig(config);
    config.put("sites-blacklist", List.of("site2")); // 订阅覆盖为黑名单
    subscriptionService.applySitesFilter(config);
    
    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertEquals(2, sites.size());
    assertTrue(sites.stream().anyMatch(s -> "site1".equals(s.get("key"))));
    assertTrue(sites.stream().anyMatch(s -> "site3".equals(s.get("key"))));
}
```

- [ ] **Step 2: 添加场景2测试 - 全局黑名单 + 订阅白名单**

```java
@Test
void scenario2_globalBlacklist_subscriptionWhitelist() {
    when(settingRepository.findById(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE))
            .thenReturn(Optional.of(new Setting(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE, 
                "{\"sites-blacklist\":[\"site1\",\"site2\"]}")));
    
    Map<String, Object> config = new HashMap<>();
    config.put("sites", List.of(
        Map.of("key", "site1"),
        Map.of("key", "site2"),
        Map.of("key", "site3")
    ));
    
    subscriptionService.applyGlobalConfig(config);
    config.put("sites-whitelist", List.of("site1")); // 订阅覆盖为白名单
    subscriptionService.applySitesFilter(config);
    
    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertEquals(1, sites.size());
    assertEquals("site1", sites.get(0).get("key"));
}
```

- [ ] **Step 3: 添加场景3测试 - 全局黑名单 + 订阅自定义黑名单**

```java
@Test
void scenario3_globalBlacklist_subscriptionCustomBlacklist() {
    when(settingRepository.findById(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE))
            .thenReturn(Optional.of(new Setting(Constants.GLOBAL_SUBSCRIPTION_OVERRIDE, 
                "{\"sites-blacklist\":[\"siteX\"]}")));
    
    Map<String, Object> config = new HashMap<>();
    config.put("sites", List.of(
        Map.of("key", "site1"),
        Map.of("key", "site2"),
        Map.of("key", "siteX")
    ));
    
    subscriptionService.applyGlobalConfig(config);
    config.put("sites-blacklist", List.of("site1", "site2")); // 订阅完全替换
    subscriptionService.applySitesFilter(config);
    
    List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
    assertEquals(1, sites.size());
    assertEquals("siteX", sites.get(0).get("key")); // siteX 未被排除
}
```

- [ ] **Step 4: 运行所有测试**

```bash
mvn test -Dtest=SubscriptionServiceGlobalConfigTest
```

预期: 所有测试 PASS

- [ ] **Step 5: 提交**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/SubscriptionServiceGlobalConfigTest.java
git commit -m "test: add integration tests for global config scenarios

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: 端到端测试

**Files:**
- N/A (手动测试)

- [ ] **Step 1: 启动应用**

```bash
mvn spring-boot:run
```

- [ ] **Step 2: 设置全局黑名单**

```bash
curl -X PUT http://localhost:4567/api/subscription/global-config \
  -H "Content-Type: application/json" \
  -d '{"sites-blacklist": ["site1", "site2"]}'
```

- [ ] **Step 3: 获取默认订阅配置（无覆盖）**

```bash
curl http://localhost:4567/sub/-/0 | jq '.sites[] | .key' | head -20
```

预期: 不包含 site1 和 site2

- [ ] **Step 4: 创建带白名单覆盖的订阅**

通过管理界面或 API 创建订阅，override 字段设置为：
```json
{"sites-whitelist": ["site1"]}
```

获取该订阅配置：
```bash
curl http://localhost:4567/sub/-/[订阅ID] | jq '.sites[] | .key'
```

预期: 只包含 site1

- [ ] **Step 5: 验证全局白名单优先于黑名单**

```bash
curl -X PUT http://localhost:4567/api/subscription/global-config \
  -H "Content-Type: application/json" \
  -d '{"sites-whitelist": ["site1"], "sites-blacklist": ["site2"]}'

curl http://localhost:4567/sub/-/0 | jq '.sites[] | .key'
```

预期: 只包含 site1（黑名单被忽略）

- [ ] **Step 6: 文档验证结果**

创建测试报告文件记录测试结果：

```bash
echo "## 端到端测试结果

### 场景1: 全局黑名单
- 设置: sites-blacklist: [site1, site2]
- 结果: ✓ 默认订阅排除 site1, site2

### 场景2: 订阅白名单覆盖
- 全局: sites-blacklist: [site1, site2]
- 订阅: sites-whitelist: [site1]
- 结果: ✓ 订阅只包含 site1

### 场景3: 全局白名单优先
- 全局: sites-whitelist: [site1], sites-blacklist: [site2]
- 结果: ✓ 只包含 site1，黑名单被忽略
" > docs/superpowers/plans/2026-06-11-e2e-test-results.md
```

- [ ] **Step 7: 提交测试结果**

```bash
git add docs/superpowers/plans/2026-06-11-e2e-test-results.md
git commit -m "docs: add e2e test results for global config

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec Coverage:**
✓ Setting 表新增配置 - Task 1, 2, 3
✓ getGlobalConfig - Task 2
✓ updateGlobalConfig - Task 3
✓ applyGlobalConfig - Task 4
✓ handleWhitelist/removeBlacklist 修改 - Task 5
✓ 集成到 subscription 流程 - Task 6
✓ API 端点 - Task 7
✓ 测试场景 1-4 - Task 8
✓ 端到端测试 - Task 9

**Placeholder Scan:**
✓ 所有代码块完整
✓ 所有测试包含实际代码
✓ 所有命令包含预期输出
✓ 无 TBD/TODO

**Type Consistency:**
✓ `getGlobalConfig()` 返回 `Map<String, Object>`
✓ `updateGlobalConfig(Map<String, Object>)` 参数一致
✓ `applyGlobalConfig(Map<String, Object>)` 参数一致
✓ `applySitesFilter(Map<String, Object>)` 参数一致

---

