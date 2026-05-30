# GuangYaPan Account And Share Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add complete GuangYaPan support: QR-code account login, account storage mounting, share import, and frontend account/share UI integration.

**Architecture:** Reuse the existing `DriverAccountService` and `ShareService` integration points that already create AList/OpenList storage rows. Add focused storage classes for `GuangYaPan` and `GuangYaPanShare`, add a small OAuth device-code helper inside `DriverAccountService`, and keep frontend changes inside the existing account/share views.

**Tech Stack:** Java 21, Spring Boot, RestTemplate, Jackson `ObjectMapper`, JUnit 5, Mockito, Vue 3, TypeScript, Element Plus.

---

## File Map

- Modify `src/main/java/cn/har01d/alist_tvbox/domain/DriverType.java`: add `GUANGYA` enum value.
- Create `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPan.java`: map `DriverAccount` fields to AList/OpenList `GuangYaPan` storage addition.
- Create `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPanShare.java`: map `Share` fields to AList/OpenList `GuangYaPanShare` storage addition.
- Modify `src/main/java/cn/har01d/alist_tvbox/storage/Storage.java`: add GuangYa account/share mount path prefixes.
- Modify `src/main/java/cn/har01d/alist_tvbox/dto/AccountInfo.java`: add `addition` map so QR login can return `refresh_token` and `device_id` without overloading `cookie`.
- Modify `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`: validate/save GuangYa accounts and implement QR device-code login/polling.
- Modify `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`: parse GuangYa share links, create GuangYa share storage, and assign share type `12`.
- Modify `src/test/java/cn/har01d/alist_tvbox/service/ShareLinkTest.java`: add GuangYa parse coverage.
- Create `src/test/java/cn/har01d/alist_tvbox/storage/GuangYaPanStorageTest.java`: assert storage addition JSON for account and share.
- Create `src/test/java/cn/har01d/alist_tvbox/service/DriverAccountServiceGuangYaTest.java`: assert account validation defaults and QR token mapping.
- Modify `web-ui/src/views/DriverAccountView.vue`: add GuangYa account option, token fields, QR mapping, icon metadata, and mount path.
- Modify `web-ui/src/views/SharesView.vue`: add GuangYa share type labels, link rendering, driver label, and icon metadata.
- Include existing `web-ui/public/guangya.webp` in implementation commit.

## Task 1: Storage Classes And Mount Paths

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/domain/DriverType.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPan.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPanShare.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/storage/Storage.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/storage/GuangYaPanStorageTest.java`

- [ ] **Step 1: Write failing storage tests**

Create `src/test/java/cn/har01d/alist_tvbox/storage/GuangYaPanStorageTest.java`:

```java
package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.Share;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuangYaPanStorageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void guangYaPanAccountBuildsExpectedAddition() throws Exception {
        DriverAccount account = new DriverAccount();
        account.setId(7);
        account.setType(DriverType.GUANGYA);
        account.setName("main");
        account.setToken("access-token");
        account.setFolder("root-folder");
        account.setAddition("{\"refresh_token\":\"refresh-token\",\"device_id\":\"0123456789abcdef0123456789abcdef\"}");

        GuangYaPan storage = new GuangYaPan(account);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals(4007, storage.getId());
        assertEquals("GuangYaPan", storage.getDriver());
        assertEquals("/我的光鸭网盘/main", storage.getPath());
        assertEquals("root-folder", addition.get("root_folder_id").asText());
        assertEquals("access-token", addition.get("access_token").asText());
        assertEquals("refresh-token", addition.get("refresh_token").asText());
        assertEquals("0123456789abcdef0123456789abcdef", addition.get("device_id").asText());
        assertEquals(100, addition.get("page_size").asInt());
        assertEquals(3, addition.get("order_by").asInt());
        assertEquals(1, addition.get("sort_type").asInt());
    }

    @Test
    void guangYaPanAccountFallsBackToAdditionAccessToken() throws Exception {
        DriverAccount account = new DriverAccount();
        account.setId(8);
        account.setType(DriverType.GUANGYA);
        account.setName("fallback");
        account.setFolder("0");
        account.setAddition("{\"access_token\":\"addition-access\",\"refresh_token\":\"refresh-token\"}");

        GuangYaPan storage = new GuangYaPan(account);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals("addition-access", addition.get("access_token").asText());
        assertEquals("refresh-token", addition.get("refresh_token").asText());
    }

    @Test
    void guangYaPanShareBuildsExpectedAddition() throws Exception {
        Share share = new Share();
        share.setId(20001);
        share.setType(12);
        share.setPath("Movies");
        share.setShareId("1894369771769081942_aeWVzywV3ZOZly47");
        share.setCookie("0123456789abcdef0123456789abcdef");

        GuangYaPanShare storage = new GuangYaPanShare(share);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals(20001, storage.getId());
        assertEquals("GuangYaPanShare", storage.getDriver());
        assertEquals("/我的光鸭分享/Movies", storage.getPath());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", addition.get("share_id").asText());
        assertEquals("0123456789abcdef0123456789abcdef", addition.get("device_id").asText());
        assertEquals(200, addition.get("page_size").asInt());
        assertEquals(0, addition.get("order_by").asInt());
        assertEquals(0, addition.get("sort_type").asInt());
    }
}
```

- [ ] **Step 2: Run storage tests to verify they fail**

Run:

```bash
mvn -Dtest=GuangYaPanStorageTest test
```

Expected: compilation fails because `DriverType.GUANGYA`, `GuangYaPan`, and `GuangYaPanShare` do not exist.

- [ ] **Step 3: Add enum, storage classes, and mount paths**

Update `src/main/java/cn/har01d/alist_tvbox/domain/DriverType.java` by inserting `GUANGYA` before `ALI`:

```java
    BAIDU,
    GUANGYA,
    ALI,
```

Create `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPan.java`:

```java
package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.util.Utils;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public class GuangYaPan extends Storage {
    public GuangYaPan(DriverAccount account) {
        super(account, "GuangYaPan");
        Map<String, Object> addition = Utils.readJson(account.getAddition());
        String accessToken = StringUtils.defaultIfBlank(account.getToken(), (String) addition.get("access_token"));
        addAddition("root_folder_id", StringUtils.defaultIfBlank(account.getFolder(), "0"));
        addAddition("access_token", StringUtils.trimToEmpty(accessToken));
        addAddition("refresh_token", StringUtils.trimToEmpty((String) addition.get("refresh_token")));
        String deviceId = StringUtils.trimToEmpty((String) addition.get("device_id"));
        if (StringUtils.isNotBlank(deviceId)) {
            addAddition("device_id", deviceId);
        }
        addAddition("page_size", intValue(addition.get("page_size"), 100));
        addAddition("order_by", intValue(addition.get("order_by"), 3));
        addAddition("sort_type", intValue(addition.get("sort_type"), 1));
        buildAddition();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.isNumeric(text)) {
            return Integer.parseInt(text);
        }
        return fallback;
    }
}
```

Create `src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPanShare.java`:

```java
package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;
import org.apache.commons.lang3.StringUtils;

public class GuangYaPanShare extends Storage {
    public GuangYaPanShare(Share share) {
        super(share, "GuangYaPanShare");
        addAddition("share_id", share.getShareId());
        if (StringUtils.isNotBlank(share.getCookie())) {
            addAddition("device_id", share.getCookie().trim());
        }
        addAddition("page_size", 200);
        addAddition("order_by", 0);
        addAddition("sort_type", 0);
        buildAddition();
    }
}
```

Update `Storage.getMountPath(Share share)` with type `12` before type `11`:

```java
        } else if (share.getType() == 12) {
            return "/我的光鸭分享/" + path;
        } else if (share.getType() == 11) {
            return "/strm/" + path;
        }
```

Update `Storage.getMountPath(DriverAccount account)` before the final fallback:

```java
        } else if (account.getType() == DriverType.GUANGYA) {
            return "/我的光鸭网盘/" + account.getName();
        }
```

- [ ] **Step 4: Run storage tests**

Run:

```bash
mvn -Dtest=GuangYaPanStorageTest test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/cn/har01d/alist_tvbox/domain/DriverType.java src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPan.java src/main/java/cn/har01d/alist_tvbox/storage/GuangYaPanShare.java src/main/java/cn/har01d/alist_tvbox/storage/Storage.java src/test/java/cn/har01d/alist_tvbox/storage/GuangYaPanStorageTest.java
git commit -m "feat: add guangyapan storage mappings"
```

## Task 2: Account Service Integration And QR Login

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/AccountInfo.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/DriverAccountServiceGuangYaTest.java`

- [ ] **Step 1: Write failing service tests**

Create `src/test/java/cn/har01d/alist_tvbox/service/DriverAccountServiceGuangYaTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.AccountInfo;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PanAccountRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverAccountServiceGuangYaTest {
    @Mock PanAccountRepository panAccountRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock SettingRepository settingRepository;
    @Mock ShareRepository shareRepository;
    @Mock AccountService accountService;
    @Mock AListLocalService aListLocalService;
    @Mock OfflineDownloadService offlineDownloadService;
    @Mock JdbcTemplate alistJdbcTemplate;

    @Test
    void createGuangYaAccountDefaultsFolderAndSavesStorage() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(mock(RestTemplate.class));
        when(driverAccountRepository.existsByNameAndType("main", DriverType.GUANGYA)).thenReturn(false);
        when(driverAccountRepository.countByType(DriverType.GUANGYA)).thenReturn(0L);
        when(driverAccountRepository.save(any(DriverAccount.class))).thenAnswer(invocation -> {
            DriverAccount account = invocation.getArgument(0);
            account.setId(9);
            return account;
        });
        when(aListLocalService.checkStatus()).thenReturn(0);
        when(settingRepository.findById("quark_device_id")).thenReturn(Optional.empty());

        DriverAccountService service = new DriverAccountService(panAccountRepository, driverAccountRepository,
                settingRepository, shareRepository, accountService, aListLocalService, offlineDownloadService,
                builder, new ObjectMapper(), alistJdbcTemplate);

        DriverAccount account = new DriverAccount();
        account.setName("main");
        account.setType(DriverType.GUANGYA);
        account.setToken("access-token");
        account.setAddition("{\"refresh_token\":\"refresh-token\"}");

        service.create(account);

        ArgumentCaptor<DriverAccount> captor = ArgumentCaptor.forClass(DriverAccount.class);
        verify(driverAccountRepository).save(captor.capture());
        assertEquals("0", captor.getValue().getFolder());
        verify(aListLocalService).saveStorage(any(cn.har01d.alist_tvbox.storage.GuangYaPan.class));
    }

    @Test
    void createGuangYaAccountRequiresTokenData() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(mock(RestTemplate.class));
        DriverAccountService service = new DriverAccountService(panAccountRepository, driverAccountRepository,
                settingRepository, shareRepository, accountService, aListLocalService, offlineDownloadService,
                builder, new ObjectMapper(), alistJdbcTemplate);

        DriverAccount account = new DriverAccount();
        account.setName("main");
        account.setType(DriverType.GUANGYA);
        account.setAddition("{}");

        assertThrows(BadRequestException.class, () -> service.create(account));
    }

    @Test
    void accountInfoCanCarryGuangYaAddition() {
        AccountInfo info = new AccountInfo();
        info.getAddition().put("refresh_token", "refresh-token");
        info.getAddition().put("device_id", "0123456789abcdef0123456789abcdef");

        assertEquals("refresh-token", info.getAddition().get("refresh_token"));
        assertEquals("0123456789abcdef0123456789abcdef", info.getAddition().get("device_id"));
    }
}
```

- [ ] **Step 2: Run service tests to verify they fail**

Run:

```bash
mvn -Dtest=DriverAccountServiceGuangYaTest test
```

Expected: compilation fails because `AccountInfo.getAddition()` and `DriverType.GUANGYA` service handling are missing.

- [ ] **Step 3: Add AccountInfo addition map**

Modify `src/main/java/cn/har01d/alist_tvbox/dto/AccountInfo.java`:

```java
package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AccountInfo {
    private String id;
    private String name;
    private String cookie;
    private String token;
    private String vip = "user";
    private Map<String, Object> addition = new HashMap<>();
}
```

- [ ] **Step 4: Add GuangYa account validation and storage mapping**

Modify imports in `DriverAccountService.java` to include `GuangYaPan`, `SecureRandom`, `Base64`, and `UUID` only if used by final code:

```java
import cn.har01d.alist_tvbox.storage.GuangYaPan;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
```

Add constants near the existing fields:

```java
    private static final String GY_ACCOUNT_API = "https://account.guangyapan.com";
    private static final String GY_CLIENT_ID = "aMe-8VSlkrbQXpUR";
    private static final String GY_DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
```

In `saveStorage`, add GuangYa mapping before `BAIDU` or after it:

```java
        } else if (account.getType() == DriverType.GUANGYA) {
            storage = new GuangYaPan(account);
        } else if (account.getType() == DriverType.BAIDU) {
            storage = new BaiduNetdisk(account);
        }
```

In `validate`, add a GuangYa-specific branch before the generic cookie/token branch:

```java
        } else if (dto.getType() == DriverType.GUANGYA) {
            Map<String, Object> addition = Utils.readJson(dto.getAddition());
            String accessToken = StringUtils.defaultIfBlank(dto.getToken(), (String) addition.get("access_token"));
            String refreshToken = (String) addition.get("refresh_token");
            if (StringUtils.isBlank(accessToken) && StringUtils.isBlank(refreshToken)) {
                throw new BadRequestException("Token不能为空");
            }
```

In the empty-folder default condition, include `DriverType.GUANGYA`:

```java
            if (dto.getType() == DriverType.QUARK || dto.getType() == DriverType.UC || dto.getType() == DriverType.QUARK_TV || dto.getType() == DriverType.UC_TV || dto.getType() == DriverType.PAN115 || dto.getType() == DriverType.OPEN115 || dto.getType() == DriverType.PAN123 || dto.getType() == DriverType.GUANGYA) {
                dto.setFolder("0");
```

- [ ] **Step 5: Add GuangYa QR login skeleton**

In `getQrCode`, add before the `QuarkUCTV driver = drivers.get(type);` block:

```java
        if (DriverType.GUANGYA.name().equals(type)) {
            return getGuangYaQr();
        }
```

In `getRefreshToken`, add before the `QuarkUCTV driver = drivers.get(type);` block:

```java
        if (DriverType.GUANGYA.name().equals(type)) {
            return getGuangYaToken(queryToken);
        }
```

Add helper methods to `DriverAccountService` before `getQuarkQr()`:

```java
    private QuarkUCTV.LoginResponse getGuangYaQr() throws IOException {
        String deviceId = createGuangYaDeviceId();
        HttpHeaders headers = guangYaHeaders(deviceId);
        Map<String, Object> body = Map.of("scope", "user", "client_id", GY_CLIENT_ID);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ObjectNode json = restTemplate.postForObject(GY_ACCOUNT_API + "/v1/auth/device/code", entity, ObjectNode.class);
        if (json == null) {
            throw new BadRequestException("二维码生成失败: empty response");
        }
        String deviceCode = json.path("device_code").asText("");
        String verifyUrl = json.path("verification_uri_complete").asText(json.path("verification_url").asText(""));
        int expiresIn = json.path("expires_in").asInt(120);
        if (StringUtils.isAnyBlank(deviceCode, verifyUrl)) {
            String error = StringUtils.defaultIfBlank(json.path("error_description").asText(), json.path("message").asText("invalid response"));
            throw new BadRequestException("二维码生成失败: " + error);
        }

        var res = new QuarkUCTV.LoginResponse();
        res.setQrData(Utils.getQrCode(verifyUrl));
        res.setQueryToken(encodeGuangYaSession(deviceCode, deviceId, expiresIn));
        return res;
    }

    private AccountInfo getGuangYaToken(String queryToken) {
        Map<String, Object> session = decodeGuangYaSession(queryToken);
        if (session.isEmpty()) {
            throw new BadRequestException("二维码无效或已过期！");
        }
        String deviceCode = (String) session.get("device_code");
        String deviceId = (String) session.get("device_id");
        HttpHeaders headers = guangYaHeaders(deviceId);
        Map<String, Object> body = Map.of(
                "grant_type", GY_DEVICE_GRANT,
                "device_code", deviceCode,
                "client_id", GY_CLIENT_ID
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ObjectNode json = restTemplate.postForObject(GY_ACCOUNT_API + "/v1/auth/token", entity, ObjectNode.class);
        if (json == null) {
            throw new BadRequestException("等待用户扫码...");
        }

        String accessToken = json.path("access_token").asText(json.path("accessToken").asText(""));
        String refreshToken = json.path("refresh_token").asText(json.path("refreshToken").asText(""));
        if (StringUtils.isNotBlank(accessToken) || StringUtils.isNotBlank(refreshToken)) {
            var info = new AccountInfo();
            info.setToken(accessToken);
            info.getAddition().put("access_token", accessToken);
            info.getAddition().put("refresh_token", refreshToken);
            info.getAddition().put("device_id", deviceId);
            return info;
        }

        String error = json.path("error").asText("");
        if ("access_denied".equals(error)) {
            throw new BadRequestException("用户已取消扫码");
        }
        if (error.contains("expired") || "invalid_grant".equals(error)) {
            throw new BadRequestException("二维码无效或已过期！");
        }
        throw new BadRequestException("等待用户扫码...");
    }

    private HttpHeaders guangYaHeaders(String deviceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.set("X-Client-Id", GY_CLIENT_ID);
        headers.set("X-Client-Version", "0.0.1");
        headers.set("X-Device-Id", deviceId);
        headers.set("X-Device-Model", "chrome%2F147.0.0.0");
        headers.set("X-Device-Name", "PC-Chrome");
        headers.set("X-Device-Sign", "wdi10." + deviceId + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        headers.set("X-Net-Work-Type", "NONE");
        headers.set("X-OS-Version", "Win32");
        headers.set("X-Platform-Version", "1");
        headers.set("X-Protocol-Version", "301");
        headers.set("X-Provider-Name", "NONE");
        headers.set("X-SDK-Version", "9.0.2");
        return headers;
    }
```

Add session helpers near the QR helpers:

```java
    private String encodeGuangYaSession(String deviceCode, String deviceId, int expiresIn) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("device_code", deviceCode);
        node.put("device_id", deviceId);
        node.put("expire_time", System.currentTimeMillis() + Math.max(1000, expiresIn * 1000L));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private Map<String, Object> decodeGuangYaSession(String queryToken) {
        if (StringUtils.isBlank(queryToken)) {
            return Map.of();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(queryToken);
            Map<String, Object> data = objectMapper.readValue(bytes, Map.class);
            Number expireTime = (Number) data.get("expire_time");
            if (expireTime == null || expireTime.longValue() < System.currentTimeMillis()) {
                return Map.of();
            }
            return data;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String createGuangYaDeviceId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
```

- [ ] **Step 6: Run service tests**

Run:

```bash
mvn -Dtest=DriverAccountServiceGuangYaTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add src/main/java/cn/har01d/alist_tvbox/dto/AccountInfo.java src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java src/test/java/cn/har01d/alist_tvbox/service/DriverAccountServiceGuangYaTest.java
git commit -m "feat: add guangyapan account login support"
```

## Task 3: Share Parsing And Share Storage Integration

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/ShareLinkTest.java`

- [ ] **Step 1: Add failing share parsing tests**

Append these assertions inside `ShareLinkTest.parseLink()` near the other provider assertions:

```java
        share = new Share();
        share.setShareId("https://www.guangyapan.com/s/1894369771769081942_aeWVzywV3ZOZly47");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", share.getShareId());

        share = new Share();
        share.setShareId("https://guangyapan.com/s/1894369771769081942_aeWVzywV3ZOZly47#/folder");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", share.getShareId());

        share = new Share();
        share.setShareId("1894369771769081942_aeWVzywV3ZOZly47");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", share.getShareId());

        share = new Share();
        share.setShareId("not_a_valid_guangya_share");
        assertFalse(shareService.parseLink(share));
```

- [ ] **Step 2: Run share test to verify it fails**

Run:

```bash
mvn -Dtest=ShareLinkTest#parseLink test
```

Expected: FAIL because GuangYa links are not parsed.

- [ ] **Step 3: Add GuangYa share parsing and storage mapping**

Add import to `ShareService.java`:

```java
import cn.har01d.alist_tvbox.storage.GuangYaPanShare;
```

Add patterns near the existing share patterns:

```java
    private static final Pattern SHARE_GUANGYA_LINK = Pattern.compile("https://(?:www\\.)?guangyapan\\.com/s/([A-Za-z0-9_-]+)");
    private static final Pattern SHARE_GUANGYA_ID = Pattern.compile("^[A-Za-z0-9]+_[A-Za-z0-9_-]+$");
```

In `saveStorage(Share share, boolean disabled)`, add before STRM:

```java
        } else if (share.getType() == 12) {
            storage = new GuangYaPanShare(share);
        } else if (share.getType() == 11) {
            storage = new StrmStorage(share);
        }
```

In `parseLink(Share share)`, add before `return false;`:

```java
        m = SHARE_GUANGYA_LINK.matcher(url);
        if (m.find()) {
            share.setType(12);
            share.setShareId(m.group(1));
            share.setPassword("");
            return true;
        }

        if (SHARE_GUANGYA_ID.matcher(url).matches()) {
            share.setType(12);
            share.setShareId(url);
            share.setPassword("");
            return true;
        }
```

- [ ] **Step 4: Run share parsing tests**

Run:

```bash
mvn -Dtest=ShareLinkTest#parseLink test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/ShareService.java src/test/java/cn/har01d/alist_tvbox/service/ShareLinkTest.java
git commit -m "feat: parse guangyapan share links"
```

## Task 4: Frontend Account UI

**Files:**
- Modify: `web-ui/src/views/DriverAccountView.vue`
- Include: `web-ui/public/guangya.webp`

- [ ] **Step 1: Add GuangYa account option and mount path**

In the account type table template, add after Baidu or before it:

```vue
          <span v-else-if="scope.row.type=='GUANGYA'">光鸭网盘</span>
```

In the radio group, add:

```vue
            <el-radio label="GUANGYA" size="large">光鸭网盘</el-radio>
```

In `fullPath`, add before the final fallback:

```ts
  } else if (share.type == 'GUANGYA') {
    return '/我的光鸭网盘/' + path
```

In `getTypeName`, add `GUANGYA` mapping if the function uses explicit branches:

```ts
  if (type == 'GUANGYA') {
    return '光鸭网盘'
  }
```

- [ ] **Step 2: Add GuangYa token fields and QR handling**

Add a token form item near other token fields:

```vue
        <el-form-item label="Token" v-if="form.type=='GUANGYA'" required>
          <el-input v-model="form.token" type="textarea" :rows="3"/>
          <el-button type="primary" @click="showQrCode">扫码获取</el-button>
        </el-form-item>
```

Add GuangYa fields to the default `addition` object in both `form` initialization locations:

```ts
    refresh_token: '',
    device_id: '',
```

Update `getRefreshToken` so GuangYa QR success stores addition fields:

```ts
    if (qrType.value == 'QUARK' || qrType.value == 'UC') {
      form.value.cookie = data.cookie
    } else {
      form.value.token = data.token
    }
    if (qrType.value == 'GUANGYA' && data.addition) {
      form.value.addition.access_token = data.addition.access_token || data.token || ''
      form.value.addition.refresh_token = data.addition.refresh_token || ''
      form.value.addition.device_id = data.addition.device_id || ''
    }
```

Keep the existing name fill behavior.

- [ ] **Step 3: Include GuangYa in proxy type metadata only if provider icon arrays exist**

Search in `DriverAccountView.vue` for image arrays or provider metadata. If none exist, do not add unused metadata. If provider icon rendering exists in this file after local context inspection, add:

```ts
{key: 'GUANGYA', label: '光鸭网盘', icon: '/guangya.webp'}
```

Do not add GuangYa to local proxy config unless the existing backend proxy config supports it.

- [ ] **Step 4: Run frontend syntax check**

Run:

```bash
npm --prefix web-ui run build
```

Expected: build completes successfully.

- [ ] **Step 5: Commit Task 4**

```bash
git add web-ui/src/views/DriverAccountView.vue web-ui/public/guangya.webp
git commit -m "feat: add guangyapan account ui"
```

## Task 5: Frontend Share UI

**Files:**
- Modify: `web-ui/src/views/SharesView.vue`

- [ ] **Step 1: Add GuangYa share display branches**

In the share link column, add after Baidu or before the final empty branch:

```vue
        <a v-else-if="scope.row.type == 12" :href="getShareLink(scope.row)" target="_blank">
          https://www.guangyapan.com/s/{{ scope.row.shareId }}
        </a>
```

In the share type column, add:

```vue
        <span v-else-if="scope.row.type == 12">光鸭分享</span>
```

In the failed storage driver label column, add:

```vue
        <span v-else-if="scope.row.driver == 'GuangYaPanShare'">光鸭分享</span>
```

- [ ] **Step 2: Add GuangYa share type option and path/link builders**

Find `options` in `SharesView.vue` and add:

```ts
  {value: 12, label: '光鸭分享'},
```

In `fullPath`, add before local/STRM/fallback handling:

```ts
  } else if (share.type == 12) {
    return '/我的光鸭分享/' + path
```

In `getShareLink`, add before the Alipan fallback:

```ts
  } else if (shareInfo.type == 12) {
    url = 'https://www.guangyapan.com/s/' + shareInfo.shareId
```

Ensure password query appending is skipped for type `12` by keeping type `12` out of the password-specific branch or adding this guard before appending:

```ts
  if (shareInfo.password && shareInfo.type != 12) {
```

- [ ] **Step 3: Add provider icon metadata only if the share UI renders icons**

Search in `SharesView.vue` for image/icon metadata. If present, add:

```ts
{type: 12, label: '光鸭分享', icon: '/guangya.webp'}
```

If no icon rendering exists, leave this out; the asset is already available for future icon rendering.

- [ ] **Step 4: Run frontend build**

Run:

```bash
npm --prefix web-ui run build
```

Expected: build completes successfully.

- [ ] **Step 5: Commit Task 5**

```bash
git add web-ui/src/views/SharesView.vue
git commit -m "feat: add guangyapan share ui"
```

## Task 6: Final Verification

**Files:**
- No code changes expected unless verification finds defects.

- [ ] **Step 1: Run targeted backend tests**

Run:

```bash
mvn -Dtest=GuangYaPanStorageTest,DriverAccountServiceGuangYaTest,ShareLinkTest test
```

Expected: PASS.

- [ ] **Step 2: Run broader backend tests for touched service area**

Run:

```bash
mvn -Dtest=ShareLinkTest,DriverAccountServiceGuangYaTest test
```

Expected: PASS.

- [ ] **Step 3: Run frontend build**

Run:

```bash
npm --prefix web-ui run build
```

Expected: PASS.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: only intentional uncommitted files remain, or a clean working tree except pre-existing unrelated untracked files.

- [ ] **Step 5: Commit verification fixes if any**

If verification required fixes, commit them with:

```bash
git add <fixed-files>
git commit -m "fix: stabilize guangyapan support"
```

If no fixes were needed, do not create an empty commit.

## Self-Review

- Spec coverage: account driver, share driver, QR login, share parsing, frontend account/share labels, `guangya.webp`, and tests are each mapped to tasks.
- Scope: no direct GuangYa file API implementation, no SMS login, no offline download.
- Type consistency: account type is `DriverType.GUANGYA`; AList drivers are `GuangYaPan` and `GuangYaPanShare`; share type is numeric `12`; frontend route asset is `/guangya.webp`.
- No placeholders: task steps include concrete files, code snippets, commands, and expected outcomes.
