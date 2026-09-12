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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverAccountServicePan123Test {
    @Mock
    PanAccountRepository panAccountRepository;
    @Mock
    DriverAccountRepository driverAccountRepository;
    @Mock
    SettingRepository settingRepository;
    @Mock
    ShareRepository shareRepository;
    @Mock
    AccountService accountService;
    @Mock
    AListLocalService aListLocalService;
    @Mock
    OfflineDownloadService offlineDownloadService;
    @Mock
    JdbcTemplate alistJdbcTemplate;

    private DriverAccountService newService(RestTemplate restTemplate) {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        return new DriverAccountService(panAccountRepository, driverAccountRepository,
                settingRepository, shareRepository, accountService, aListLocalService, offlineDownloadService,
                builder, new ObjectMapper(), alistJdbcTemplate, mock(cn.har01d.alist_tvbox.service.Index115SiteSeed.class));
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DriverAccount account() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN123);
        account.setUsername("15828249500");
        account.setPassword("secret");
        return account;
    }

    @Test
    void pan123AccountInfoUsesDriverContract() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DriverAccountService service = newService(restTemplate);
        String storageAddition = "{\"accesstoken\":\"token-123\",\"loginuuid\":\"login-uuid-1\",\"platform\":\"web\"}";
        when(alistJdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(4012))).thenReturn(storageAddition);
        String body = "{\"code\":0,\"message\":\"ok\",\"data\":{\"UID\":1823458492,\"Nickname\":\"Har01d\","
                + "\"SpaceUsed\":46549767637,\"SpacePermanent\":2199023255552,\"SpaceTemp\":0,\"FileCount\":73,"
                + "\"SpaceTempExpr\":\"0001-01-01T00:00:00+00:00\",\"Passport\":15828249500,\"Vip\":false,"
                + "\"VipExpire\":\"1970-01-01\"}}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(ObjectNode.class)))
                .thenReturn(ResponseEntity.ok(parse(body)));

        AccountInfo info = service.getInfo(account());

        assertEquals("1823458492", info.getId());
        assertEquals("Har01d", info.getName());
        assertEquals("普通用户", info.getVip());
        assertNull(info.getExpireAt());
        assertEquals(46549767637L, info.getUsedCapacity());
        assertEquals(2199023255552L, info.getTotalCapacity());
        assertEquals(2199023255552L, ((Number) info.getAddition().get("permanentCapacity")).longValue());
        assertEquals(0L, ((Number) info.getAddition().get("temporaryCapacity")).longValue());
        assertEquals(73L, ((Number) info.getAddition().get("fileCount")).longValue());
        assertNull(info.getAddition().get("temporaryExpireAt"));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), entityCaptor.capture(), eq(ObjectNode.class));
        assertTrue(urlCaptor.getValue().startsWith("https://yun.123pan.com/api/user/info?auth-key="));
        var headers = entityCaptor.getValue().getHeaders();
        assertEquals("Bearer token-123", headers.getFirst("Authorization"));
        assertEquals("login-uuid-1", headers.getFirst("loginuuid"));
        assertEquals("android", headers.getFirst("platform"));
        assertEquals("70", headers.getFirst("app-version"));
        assertEquals("123pan/v2.4.8(Android_8.1.0;Xiaomi M1810E5A)", headers.getFirst("User-Agent"));
    }

    @Test
    void pan123AccountInfoReloginsWhenTokenExpired() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DriverAccountService service = newService(restTemplate);
        String storageAddition = "{\"accesstoken\":\"token-expired\",\"loginuuid\":\"login-uuid-1\"}";
        when(alistJdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(4012))).thenReturn(storageAddition);
        String unauthorized = "{\"code\":401,\"message\":\"用户未登录\"}";
        String body = "{\"code\":0,\"message\":\"ok\",\"data\":{\"UID\":1823458492,\"Nickname\":\"Har01d\","
                + "\"SpaceUsed\":1,\"SpacePermanent\":100,\"SpaceTemp\":0,\"FileCount\":1}}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(ObjectNode.class)))
                .thenReturn(ResponseEntity.ok(parse(unauthorized)), ResponseEntity.ok(parse(body)));
        when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(ObjectNode.class)))
                .thenReturn(parse("{\"code\":200,\"message\":\"ok\",\"data\":{\"token\":\"token-fresh\"}}"));

        AccountInfo info = service.getInfo(account());

        assertEquals("Har01d", info.getName());

        // 重登走驱动同款 sign_in 契约
        ArgumentCaptor<String> postUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> postEntity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(postUrl.capture(), postEntity.capture(), eq(ObjectNode.class));
        assertTrue(postUrl.getValue().startsWith("https://login.123pan.com/api/user/sign_in?auth-key="));
        Map<String, Object> loginBody = (Map<String, Object>) postEntity.getValue().getBody();
        assertEquals("15828249500", loginBody.get("passport"));
        assertEquals("secret", loginBody.get("password"));
        assertEquals(1, loginBody.get("type"));

        // 重试 user/info 用新 token
        ArgumentCaptor<HttpEntity> getCalls = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(2)).exchange(any(String.class), eq(HttpMethod.GET),
                getCalls.capture(), eq(ObjectNode.class));
        assertEquals("Bearer token-fresh", getCalls.getAllValues().get(1).getHeaders().getFirst("Authorization"));

        // 新 token 回写 addition 与账号行
        verify(alistJdbcTemplate).update(eq("UPDATE x_storages SET addition = ? WHERE id = ?"),
                contains("token-fresh"), eq(4012));
        ArgumentCaptor<DriverAccount> saved = ArgumentCaptor.forClass(DriverAccount.class);
        verify(driverAccountRepository).save(saved.capture());
        assertEquals("token-fresh", saved.getValue().getToken());
    }

    @Test
    void pan123ReloginFailureSurfacesMessage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DriverAccountService service = newService(restTemplate);
        String storageAddition = "{\"accesstoken\":\"token-expired\",\"loginuuid\":\"login-uuid-1\"}";
        when(alistJdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(4012))).thenReturn(storageAddition);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(ObjectNode.class)))
                .thenReturn(ResponseEntity.ok(parse("{\"code\":401,\"message\":\"用户未登录\"}")));
        when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(ObjectNode.class)))
                .thenReturn(parse("{\"code\":1001,\"message\":\"密码错误\"}"));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.getInfo(account()));
        assertTrue(ex.getMessage().contains("密码错误"));
    }

    @Test
    void pan123AccountInfoRequiresCredentials() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DriverAccountService service = newService(restTemplate);

        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN123);
        account.setUsername("15828249500");
        when(alistJdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(4012))).thenReturn("{}");

        assertThrows(BadRequestException.class, () -> service.getInfo(account));
    }
}
