package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigRequest;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.dto.OfflineDownloadRequest;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfflineDownloadServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private OfflineDownloadTaskRepository offlineDownloadTaskRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    private OfflineDownloadService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(restTemplateBuilder.build()).thenReturn(restTemplate);
        service = new OfflineDownloadService(
                settingRepository,
                driverAccountRepository,
                tvBoxService,
                subscriptionService,
                offlineDownloadTaskRepository,
                restTemplateBuilder,
                objectMapper
        );
    }

    @Test
    void shouldBuildDefaultRestTemplate() {
        verify(restTemplateBuilder).build();
    }

    @Test
    void saveConfigShouldRejectMissingAccountWhenEnabled() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", null)));

        assertEquals("请选择离线下载账号", exception.getMessage());
    }

    @Test
    void getQuotaShouldReturn115QuotaInfo() {
        DriverAccount account = account(12, "🈲我的115云盘", "3425588780152254335",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=get_quota_package_info&uid=6338615"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> entity = invocation.getArgument(2);
                    assertCookieHeaders(entity, account.getCookie(), "https://115.com/");
                    return textHtmlJson(quotaResponse());
                });

        OfflineDownloadQuotaResponse result = service.getQuota();

        assertEquals(1371, result.surplus());
        assertEquals(1500, result.count());
        assertEquals(129, result.used());
    }

    @Test
    void saveConfigShouldPersistWithoutSyncingAList() {
        DriverAccount account = account(12, "🈲我的115云盘", "3425588780152254335",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://webapi.115.com/files?aid=1&cid=3425588780152254335&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(folderListResponse(false)));
        when(restTemplate.exchange(eq("https://webapi.115.com/files/add"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> entity = invocation.getArgument(2);
                    assertCookieHeaders(entity, account.getCookie(), "https://115.com/");
                    @SuppressWarnings("unchecked")
                    MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entity.getBody();
                    assertEquals("3425588780152254335", body.getFirst("pid"));
                    assertEquals("alist-tvbox-offline", body.getFirst("cname"));
                    return textHtmlJson(createFolderResponse("3142159731515950166"));
                });

        OfflineDownloadConfigDto response = service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", 12));

        verify(settingRepository).save(any(Setting.class));
        assertEquals("/115云盘/🈲我的115云盘", response.folder());
    }

    @Test
    void saveConfigShouldReuseExistingOfflineFolderId() {
        DriverAccount account = account(12, "🈲我的115云盘", "3425588780152254335",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://webapi.115.com/files?aid=1&cid=3425588780152254335&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(folderListResponse(true)));

        service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", 12));

        verify(restTemplate, never()).exchange(eq("https://webapi.115.com/files/add"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void saveConfigShouldRejectBlankMountPath() {
        DriverAccount account = account(12, "", "3425588780152254335",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", 12)));

        assertEquals("离线下载账号挂载目录不能为空", exception.getMessage());
    }

    @Test
    void saveConfigShouldRejectThunderDriverType() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "THUNDER", 13)));

        assertEquals("当前仅支持115云盘离线下载", exception.getMessage());
    }

    @Test
    void getConfigShouldReturnMountPathFor115Account() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166", "cookie");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        OfflineDownloadConfigDto response = service.getConfig();

        assertEquals("/115云盘/🈲我的115云盘", response.folder());
    }

    @Test
    void syncConfiguredTempDirOnStartupShouldNotCallAList() {
        service.syncConfiguredTempDirOnStartup();
    }

    @Test
    void syncSelectedAccountTempDirShouldRefreshOfflineFolderIdForConfiguredAccount() throws Exception {
        DriverAccount account = account(12, "🈲我的115云盘", "new-parent-id",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"old-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://webapi.115.com/files?aid=1&cid=new-parent-id&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(folderListResponse("new-folder-id")));

        service.syncSelectedAccountTempDir(12);

        var setting = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(setting.capture());
        ObjectNode saved = (ObjectNode) objectMapper.readTree(setting.getValue().getValue());
        assertEquals(true, saved.path("enabled").asBoolean());
        assertEquals("PAN115", saved.path("driverType").asText());
        assertEquals(12, saved.path("accountId").asInt());
        assertEquals("new-folder-id", saved.path("offlineFolderId").asText());
    }

    @Test
    void syncConfiguredTempDirOnStartupShouldRefreshOfflineFolderId() throws Exception {
        DriverAccount account = account(12, "🈲我的115云盘", "startup-parent-id",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"stale-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://webapi.115.com/files?aid=1&cid=startup-parent-id&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(folderListResponse("startup-folder-id")));

        service.syncConfiguredTempDirOnStartup();

        var setting = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(setting.capture());
        ObjectNode saved = (ObjectNode) objectMapper.readTree(setting.getValue().getValue());
        assertEquals("startup-folder-id", saved.path("offlineFolderId").asText());
    }

    @Test
    void syncConfiguredTempDirOnStartupShouldIgnoreRestClientException() {
        DriverAccount account = account(12, "🈲我的115云盘", "startup-parent-id",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"stale-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://webapi.115.com/files?aid=1&cid=startup-parent-id&offset=0&limit=20&type=0&show_dir=1&fc_mix=0&natsort=1&count_folders=1&format=json&custom_order=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("115 unavailable"));

        service.syncConfiguredTempDirOnStartup();

        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    void downloadShouldRejectUnsupportedScheme() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadRequest("ftp://example.com/test"), ""));

        assertEquals("不支持的离线下载链接", exception.getMessage());
    }

    @Test
    void downloadShouldRejectDisabledConfig() {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.empty());

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), ""));

        assertEquals("离线下载未开启", exception.getMessage());
    }

    @Test
    void downloadShouldRejectThunderConfig() {
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"THUNDER\",\"accountId\":13}")));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), ""));

        assertEquals("当前仅支持115云盘离线下载", exception.getMessage());
    }

    @Test
    void downloadShouldAppendPlaylistForFolderResult() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("1");
        movieList.getList().add(detail);
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(tvBoxService.getDetail("", "1$/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务/~playlist")).thenReturn(movieList);
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> entity = invocation.getArgument(2);
                    assertCookieHeaders(entity, account.getCookie(), "https://115.com/");
                    String body = entity.getBody().toString();
                    assertTrue(body.contains("sign=sign-value"));
                    assertTrue(body.contains("time=1778368286"));
                    assertTrue(body.contains("uid=6338615"));
                    assertTrue(body.contains("url%5B0%5D=magnet%3A%3Fxt%3Durn%3Abtih%3Atest"));
                    assertTrue(body.contains("wp_path_id=3142159731515950166"));
                    return textHtmlJson(addTaskResponse());
                });
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        MovieList result = (MovieList) service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), "");

        assertEquals(1, result.getList().size());
        assertEquals("1", result.getList().getFirst().getVod_id());
        verify(offlineDownloadTaskRepository).save(any(OfflineDownloadTask.class));
    }

    @Test
    void downloadShouldPassAcToTvBoxDetail() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("3");
        movieList.getList().add(detail);
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(tvBoxService.getDetail("list", "1$/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务/~playlist")).thenReturn(movieList);
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTaskResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        MovieList result = (MovieList) service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), "list");

        assertEquals(1, result.getList().size());
        assertEquals("3", result.getList().getFirst().getVod_id());
    }

    @Test
    void downloadShouldKeepCurrentTokenContext() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("7");
        movieList.getList().add(detail);
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(tvBoxService.getDetail("", "1$/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务/~playlist")).thenReturn(movieList);
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTaskResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        MovieList result = (MovieList) service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), "");

        assertEquals(1, result.getList().size());
        assertEquals("7", result.getList().getFirst().getVod_id());
        verify(subscriptionService, never()).checkToken(any());
    }

    @Test
    void downloadPathShouldReturnOfflineTargetPath() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTaskResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        String result = service.downloadPath(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务", result);
    }

    @Test
    void downloadShouldNotAppendPlaylistForFileResult() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("2");
        movieList.getList().add(detail);
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(tvBoxService.getDetail("", "1$/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务.mkv")).thenReturn(movieList);
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTaskResponse()));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务.mkv", 2, false)));

        MovieList result = (MovieList) service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), "");

        assertEquals(1, result.getList().size());
        assertEquals("2", result.getList().getFirst().getVod_id());
    }

    @Test
    void downloadShouldReuseRemoteTaskWhenAddTaskReportsDuplicate() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("9");
        movieList.getList().add(detail);
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(tvBoxService.getDetail("", "1$/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务/~playlist")).thenReturn(movieList);
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        ObjectNode addTask = objectMapper.createObjectNode();
        addTask.put("state", false);
        addTask.put("error_msg", "任务已存在，请勿输入重复的链接地址");
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTask));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        MovieList result = (MovieList) service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), "");

        assertEquals(1, result.getList().size());
        assertEquals("9", result.getList().getFirst().getVod_id());
    }

    @Test
    void downloadShouldFallbackToSecondTaskListPageWhenDuplicateTaskMissesFirstPage() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(restTemplate.exchange(eq("https://115.com/?ct=clouddownload&ac=space"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(spaceResponse()));
        ObjectNode addTask = objectMapper.createObjectNode();
        addTask.put("state", false);
        addTask.put("error_msg", "任务已存在，请勿输入重复的链接地址");
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=add_task_urls"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(addTask));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:other", "别的任务", 2, true)));
        when(restTemplate.exchange(eq("https://clouddownload.115.com/web/?ac=task_lists&page=2&page_size=1000&stat=11"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(textHtmlJson(taskListResponse("magnet:?xt=urn:btih:test", "完成任务", 2, true)));

        String result = service.downloadPath(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务", result);
    }

    @Test
    void downloadPathShouldReuseCompletedLocalTaskWithoutCalling115Apis() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(completedTask("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务")));

        String result = service.downloadPath(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务", result);
        verify(restTemplate, never()).exchange(eq("https://115.com/?ct=clouddownload&ac=space"), any(), any(), eq(String.class));
    }

    @Test
    void downloadPathShouldRebuildCompletedLocalTaskPathFromCurrentMountPath() {
        DriverAccount account = account(12, "新115账号名", "3142159731515950166",
                "UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(completedTask("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务", "完成任务")));

        String result = service.downloadPath(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/新115账号名/alist-tvbox-offline/完成任务", result);
        verify(restTemplate, never()).exchange(eq("https://115.com/?ct=clouddownload&ac=space"), any(), any(), eq(String.class));
    }

    @Test
    void downloadShouldRejectInvalid115Cookie() {
        DriverAccount account = account(12, "🈲我的115云盘", "3142159731515950166", "CID=test-cid; SEID=test-seid");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.download(new OfflineDownloadRequest("magnet:?xt=urn:btih:test"), ""));

        assertEquals("115账号Cookie缺少UID", exception.getMessage());
    }

    private DriverAccount account(int id, String name, String folder, String cookie) {
        DriverAccount account = new DriverAccount();
        account.setId(id);
        account.setType(DriverType.PAN115);
        account.setName(name);
        account.setFolder(folder);
        account.setCookie(cookie);
        return account;
    }

    private ObjectNode spaceResponse() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", true);
        node.put("sign", "sign-value");
        node.put("time", 1778368286L);
        return node;
    }

    private ObjectNode addTaskResponse() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", true);
        node.put("errno", 0);
        return node;
    }

    private ObjectNode quotaResponse() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("count", 1500);
        node.put("surplus", 1371);
        node.put("used", 129);
        return node;
    }

    private ObjectNode createFolderResponse(String id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", true);
        node.put("error", "");
        node.put("errno", "");
        node.put("aid", 1);
        node.put("cid", id);
        node.put("cname", "alist-tvbox-offline");
        node.put("file_id", id);
        node.put("file_name", "alist-tvbox-offline");
        return node;
    }

    private ResponseEntity<String> textHtmlJson(ObjectNode node) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode folderListResponse(boolean exists) {
        return folderListResponse(exists ? "3142159731515950166" : null);
    }

    private ObjectNode folderListResponse(String existingId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", true);
        var data = node.putArray("data");
        if (existingId != null) {
            var item = data.addObject();
            item.put("cid", existingId);
            item.put("n", "alist-tvbox-offline");
            item.put("pid", "3425588780152254335");
        }
        return node;
    }

    private ObjectNode taskListResponse(String url, String name, int status, boolean folder) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", true);
        var tasks = node.putArray("tasks");
        var task = tasks.addObject();
        task.put("url", url);
        task.put("name", name);
        task.put("status", status);
        task.put("percentDone", status == 2 ? 100 : 0);
        task.put("info_hash", "3425507259668098980");
        task.put("file_category", folder ? 0 : 1);
        return node;
    }

    private void assertCookieHeaders(HttpEntity<?> entity, String cookie, String referer) {
        assertEquals(cookie, entity.getHeaders().getFirst(HttpHeaders.COOKIE));
        assertEquals(referer, entity.getHeaders().getFirst(HttpHeaders.REFERER));
    }

    private OfflineDownloadTask completedTask(String path) {
        return completedTask(path, "完成任务");
    }

    private OfflineDownloadTask completedTask(String path, String taskName) {
        OfflineDownloadTask task = new OfflineDownloadTask();
        task.setAccountId(12);
        task.setTargetPath(path);
        task.setTaskName(taskName);
        task.setStatus("COMPLETED");
        task.setFolder(true);
        return task;
    }
}
