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
import org.springframework.boot.restclient.RestTemplateBuilder;
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

    @Test
    void createGuangYaAccountDefaultsFolderAndSavesStorage() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(java.time.Duration.class))).thenReturn(builder);
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
        when(builder.connectTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(java.time.Duration.class))).thenReturn(builder);
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

    private DriverAccountService newService(ObjectMapper mapper) {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(java.time.Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(mock(RestTemplate.class));
        return new DriverAccountService(panAccountRepository, driverAccountRepository,
                settingRepository, shareRepository, accountService, aListLocalService, offlineDownloadService,
                builder, mapper, alistJdbcTemplate);
    }

    private DriverAccount guangYaAccount() {
        DriverAccount existing = new DriverAccount();
        existing.setId(40);
        existing.setType(DriverType.GUANGYA);
        existing.setToken("stale-access");
        existing.setAddition("{\"access_token\":\"stale-access\",\"refresh_token\":\"old-refresh\",\"device_id\":\"d1\"}");
        return existing;
    }

    @Test
    void updateTokenDualWritesGuangYaAccessToken() throws Exception {
        when(driverAccountRepository.findById(40)).thenReturn(Optional.of(guangYaAccount()));
        when(driverAccountRepository.save(any(DriverAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        DriverAccountService service = newService(new ObjectMapper());

        DriverAccount dto = new DriverAccount();
        dto.setToken("new-refresh");
        dto.setAccessToken("fresh-access");

        service.updateToken(DriverAccountService.IDX + 40, dto);

        ArgumentCaptor<DriverAccount> captor = ArgumentCaptor.forClass(DriverAccount.class);
        verify(driverAccountRepository).save(captor.capture());
        DriverAccount saved = captor.getValue();
        assertEquals("fresh-access", saved.getToken());
        var add = new ObjectMapper().readTree(saved.getAddition());
        assertEquals("new-refresh", add.path("refresh_token").asText());
        assertEquals("fresh-access", add.path("access_token").asText());
        assertEquals("d1", add.path("device_id").asText());
    }

    @Test
    void updateTokenKeepsAccessWhenSyncOmitsIt() throws Exception {
        when(driverAccountRepository.findById(40)).thenReturn(Optional.of(guangYaAccount()));
        when(driverAccountRepository.save(any(DriverAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        DriverAccountService service = newService(new ObjectMapper());

        DriverAccount dto = new DriverAccount();
        dto.setToken("new-refresh");

        service.updateToken(DriverAccountService.IDX + 40, dto);

        ArgumentCaptor<DriverAccount> captor = ArgumentCaptor.forClass(DriverAccount.class);
        verify(driverAccountRepository).save(captor.capture());
        DriverAccount saved = captor.getValue();
        assertEquals("stale-access", saved.getToken());
        var add = new ObjectMapper().readTree(saved.getAddition());
        assertEquals("new-refresh", add.path("refresh_token").asText());
        assertEquals("stale-access", add.path("access_token").asText());
    }
}
