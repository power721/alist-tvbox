package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfflineDownloadServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private AccountService accountService;
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private RestTemplate restTemplate;

    private OfflineDownloadService service;

    @BeforeEach
    void setUp() {
        service = new OfflineDownloadService(
                settingRepository,
                driverAccountRepository,
                aListLocalService,
                accountService,
                tvBoxService,
                restTemplate,
                new ObjectMapper()
        );
    }

    @Test
    void saveConfigShouldRejectMissingAccountWhenEnabled() {
        assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "PAN115", null)));
    }

    @Test
    void saveConfigShouldPersistAndSyncSet115() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setFolder("/115云盘/测试");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "PAN115", 12));

        verify(settingRepository).save(any(Setting.class));
        verify(aListLocalService).set115TempDir("/115云盘/测试/alist-tvbox-offline");
    }

    @Test
    void saveConfigShouldPersistAndSyncThunderTempDir() {
        DriverAccount account = new DriverAccount();
        account.setId(13);
        account.setType(DriverType.THUNDER);
        account.setFolder("/迅雷云盘/测试");
        when(driverAccountRepository.findById(13)).thenReturn(Optional.of(account));

        service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "THUNDER", 13));

        verify(settingRepository).save(any(Setting.class));
        verify(aListLocalService).setThunderBrowserTempDir("/迅雷云盘/测试/alist-tvbox-offline");
    }

    @Test
    void saveConfigShouldRejectMismatchedAccountType() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setFolder("/115云盘/测试");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadService.ConfigRequest(true, "THUNDER", 12)));
    }

    @Test
    void downloadShouldRejectUnsupportedScheme() {
        assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadService.DownloadRequest("ftp://example.com/test")));
    }

    @Test
    void downloadShouldRejectDisabledConfig() {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test")));
    }

    @Test
    void downloadShouldReturnPlaylistOnSuccess() throws Exception {
        DriverAccount account = new DriverAccount();
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("1");
        movieList.getList().add(detail);
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setFolder("/115云盘/测试");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(accountService.login()).thenReturn("Bearer test-token");
        when(tvBoxService.getDetail("", "1$/115云盘/测试/alist-tvbox-offline/任务名/~playlist")).thenReturn(movieList);
        when(restTemplate.postForObject(eq("/api/fs/add_offline_download"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "code", 200,
                        "data", Map.of(
                                "tasks", List.of(Map.of(
                                        "id", "task-1",
                                        "status", "pending",
                                        "name", "任务名",
                                        "progress", 0
                                ))
                        )
                ));
        when(restTemplate.postForObject(eq("/api/task/offline_download/info?tid=task-1"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "code", 200,
                        "data", Map.of(
                                "state", 2,
                                "status", "succeeded",
                                "progress", 100,
                                "error", "",
                                "name", "任务名"
                        )
        ));

        MovieList result = (MovieList) service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals(1, result.getList().size());
        assertEquals("1", result.getList().getFirst().getVod_id());
    }

    @Test
    void downloadShouldUseThunderBrowserTool() {
        DriverAccount account = new DriverAccount();
        MovieList movieList = new MovieList();
        account.setId(13);
        account.setType(DriverType.THUNDER);
        account.setFolder("/迅雷云盘/测试");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"THUNDER\",\"accountId\":13}")));
        when(driverAccountRepository.findById(13)).thenReturn(Optional.of(account));
        when(accountService.login()).thenReturn("Bearer test-token");
        when(tvBoxService.getDetail("", "1$/迅雷云盘/测试/alist-tvbox-offline/任务名/~playlist")).thenReturn(movieList);
        when(restTemplate.postForObject(eq("/api/fs/add_offline_download"), any(), eq(Map.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<Map<String, Object>> entity = invocation.getArgument(1);
                    assertEquals("ThunderBrowser", entity.getBody().get("tool"));
                    return Map.of("code", 200, "data", Map.of("tasks", List.of(Map.of("id", "task-1"))));
                });
        when(restTemplate.postForObject(eq("/api/task/offline_download/info?tid=task-1"), any(), eq(Map.class)))
                .thenReturn(Map.of("code", 200, "data", Map.of("state", 2, "name", "任务名")));

        service.download(new OfflineDownloadService.DownloadRequest("magnet:?xt=urn:btih:test"));
    }
}
