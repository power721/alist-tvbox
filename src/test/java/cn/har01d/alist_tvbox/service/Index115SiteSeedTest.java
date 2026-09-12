package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Index115SiteSeedTest {
    @Mock SiteRepository siteRepository;
    @Mock SettingRepository settingRepository;
    @Mock AListLocalService aListLocalService;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock AccountService accountService;

    Index115SiteSeed seed;

    @BeforeEach
    void setup() {
        seed = new Index115SiteSeed(siteRepository, settingRepository, aListLocalService,
                driverAccountRepository, accountService);
    }

    private Site site() {
        Site s = new Site();
        s.setId(9);
        s.setName("115分享");
        s.setUrl("http://localhost");
        s.setStorageVersion(1);
        return s;
    }

    @Test
    void runRegistersStorageDisabledWithoutAccount() {
        when(siteRepository.findAll()).thenReturn(List.of(site()));
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.empty());

        seed.run(null);

        ArgumentCaptor<Storage> captor = ArgumentCaptor.forClass(Storage.class);
        verify(aListLocalService).saveStorage(captor.capture());
        assertTrue(captor.getValue().isDisabled());
        assertEquals("/\uD83C\uDF8E我的套娃/115分享", captor.getValue().getPath());
    }

    @Test
    void runRegistersStorageEnabledWithAccount() {
        when(siteRepository.findAll()).thenReturn(List.of(site()));
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115))
                .thenReturn(Optional.of(new DriverAccount()));

        seed.run(null);

        ArgumentCaptor<Storage> captor = ArgumentCaptor.forClass(Storage.class);
        verify(aListLocalService).saveStorage(captor.capture());
        assertFalse(captor.getValue().isDisabled());
    }

    @Test
    void refreshStorageEnablesMountWhenAccountAppears() {
        when(siteRepository.findAll()).thenReturn(List.of(site()));
        when(aListLocalService.checkStatus()).thenReturn(2);
        when(accountService.login()).thenReturn("token");
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115))
                .thenReturn(Optional.of(new DriverAccount()));

        seed.refreshStorage();

        verify(accountService).deleteStorage(eq(8009), eq("token"));
        ArgumentCaptor<Storage> captor = ArgumentCaptor.forClass(Storage.class);
        verify(aListLocalService).saveStorage(captor.capture());
        assertEquals("OpenList", captor.getValue().getDriver());
        verify(accountService).enableStorage(eq(8009), eq("token"));
    }

    @Test
    void refreshStorageDisablesMountWhenAccountGone() {
        when(siteRepository.findAll()).thenReturn(List.of(site()));
        when(aListLocalService.checkStatus()).thenReturn(2);
        when(accountService.login()).thenReturn("token");
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.empty());

        seed.refreshStorage();

        verify(accountService).deleteStorage(eq(8009), eq("token"));
        ArgumentCaptor<Storage> captor = ArgumentCaptor.forClass(Storage.class);
        verify(aListLocalService).saveStorage(captor.capture());
        assertTrue(captor.getValue().isDisabled());
        verify(accountService, never()).enableStorage(anyInt(), anyString());
    }

    @Test
    void refreshStorageWithoutSiteIsNoop() {
        when(siteRepository.findAll()).thenReturn(List.of());

        seed.refreshStorage();

        verify(aListLocalService, never()).saveStorage(any());
        verifyNoInteractions(accountService);
    }
}
