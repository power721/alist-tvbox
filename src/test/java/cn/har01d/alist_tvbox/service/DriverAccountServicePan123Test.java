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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(builder.build()).thenReturn(restTemplate);
        return new DriverAccountService(panAccountRepository, driverAccountRepository,
                settingRepository, shareRepository, accountService, aListLocalService, offlineDownloadService,
                builder, new ObjectMapper(), alistJdbcTemplate);
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void pan123AccountInfoUsesWebApiContract() {
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

        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN123);
        account.setUsername("15828249500");

        AccountInfo info = service.getInfo(account);

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
        assertTrue(urlCaptor.getValue().startsWith("https://api.123278.com/b/api/user/info?1597486751="));
        var headers = entityCaptor.getValue().getHeaders();
        assertEquals("Bearer token-123", headers.getFirst("Authorization"));
        assertEquals("login-uuid-1", headers.getFirst("loginuuid"));
        assertEquals("web", headers.getFirst("platform"));
        assertEquals("3", headers.getFirst("app-version"));
    }

    @Test
    void pan123AccountInfoRequiresToken() {
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
