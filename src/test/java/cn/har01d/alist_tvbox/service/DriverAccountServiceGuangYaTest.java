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
