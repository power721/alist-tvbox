package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigDto;
import cn.har01d.alist_tvbox.dto.OfflineDownloadConfigRequest;
import cn.har01d.alist_tvbox.dto.OfflineDownloadQuotaResponse;
import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.offline.OfflineDownloadHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private OfflineDownloadTaskRepository offlineDownloadTaskRepository;
    @Mock
    private OfflineDownloadHandler pan115Handler;
    @Mock
    private OfflineDownloadHandler guangyaHandler;

    private OfflineDownloadService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(pan115Handler.getDriverType()).thenReturn(DriverType.PAN115);
        when(guangyaHandler.getDriverType()).thenReturn(DriverType.GUANGYA);
        service = new OfflineDownloadService(
                settingRepository,
                driverAccountRepository,
                offlineDownloadTaskRepository,
                objectMapper,
                List.of(pan115Handler, guangyaHandler)
        );
    }

    @Test
    void saveConfigShouldRejectMissingAccountWhenEnabled() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", null)));

        assertEquals("请选择离线下载账号", exception.getMessage());
    }

    @Test
    void saveConfigShouldPersistWithoutSyncingAList() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.ensureOfflineFolder(account)).thenReturn("3142159731515950166");

        OfflineDownloadConfigDto response = service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", 12));

        verify(settingRepository).save(any(Setting.class));
        assertEquals("/115云盘/😲我的115云盘", response.folder());
    }

    @Test
    void saveConfigShouldRejectBlankMountPath() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        account.setName("");
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "PAN115", 12)));

        assertEquals("离线下载账号挂载目录不能为空", exception.getMessage());
    }

    @Test
    void saveConfigShouldRejectUnsupportedDriverType() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.saveConfig(new OfflineDownloadConfigRequest(true, "THUNDER", 13)));

        assertTrue(exception.getMessage().contains("不支持的离线下载类型"));
    }

    @Test
    void getConfigShouldReturnMountPathFor115Account() {
        DriverAccount account = account(12, DriverType.PAN115, "3142159731515950166");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));

        OfflineDownloadConfigDto response = service.getConfig();

        assertEquals("/115云盘/😲我的115云盘", response.folder());
    }

    @Test
    void getConfigShouldReturnDefaultWhenNoSetting() {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.empty());

        OfflineDownloadConfigDto response = service.getConfig();

        assertFalse(response.enabled());
        assertEquals("PAN115", response.driverType());
    }

    @Test
    void syncConfiguredTempDirOnStartupShouldNotCallAList() {
        service.syncConfiguredTempDirOnStartup();
    }

    @Test
    void syncSelectedAccountTempDirShouldRefreshOfflineFolderIdForConfiguredAccount() throws Exception {
        DriverAccount account = account(12, DriverType.PAN115, "new-parent-id");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"old-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.ensureOfflineFolder(account)).thenReturn("new-folder-id");

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
        DriverAccount account = account(12, DriverType.PAN115, "startup-parent-id");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"stale-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.ensureOfflineFolder(account)).thenReturn("startup-folder-id");

        service.syncConfiguredTempDirOnStartup();

        var setting = org.mockito.ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(setting.capture());
        ObjectNode saved = (ObjectNode) objectMapper.readTree(setting.getValue().getValue());
        assertEquals("startup-folder-id", saved.path("offlineFolderId").asText());
    }

    @Test
    void syncConfiguredTempDirOnStartupShouldIgnoreBadRequestException() {
        DriverAccount account = account(12, DriverType.PAN115, "startup-parent-id");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"stale-folder-id\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.ensureOfflineFolder(account)).thenThrow(new BadRequestException("115 unavailable"));

        service.syncConfiguredTempDirOnStartup();

        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    void downloadPathShouldReturnOfflineTargetPath() {
        DriverAccount account = account(12, DriverType.PAN115, "3142159731515950166");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.submitAndWait(eq(account), eq("magnet:?xt=urn:btih:test"), eq("3142159731515950166")))
                .thenReturn(new OfflineDownloadHandler.TaskResult("完成任务", "hash", true));

        String result = service.downloadPath(new ParseRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/😲我的115云盘/alist-tvbox-offline/完成任务", result);
    }

    @Test
    void downloadPathShouldReuseCompletedLocalTaskWithoutCallingHandler() {
        DriverAccount account = account(12, DriverType.PAN115, "3142159731515950166");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(completedTask("/115云盘/😲我的115云盘/alist-tvbox-offline/完成任务")));

        String result = service.downloadPath(new ParseRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/😲我的115云盘/alist-tvbox-offline/完成任务", result);
        verify(pan115Handler, never()).submitAndWait(any(), any(), any());
    }

    @Test
    void downloadPathShouldRebuildCompletedLocalTaskPathFromCurrentMountPath() {
        DriverAccount account = account(12, DriverType.PAN115, "3142159731515950166");
        account.setName("新115账号名");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(completedTask("/115云盘/😲我的115云盘/alist-tvbox-offline/完成任务", "完成任务")));

        String result = service.downloadPath(new ParseRequest("magnet:?xt=urn:btih:test"));

        assertEquals("/115云盘/新115账号名/alist-tvbox-offline/完成任务", result);
        verify(pan115Handler, never()).submitAndWait(any(), any(), any());
    }

    @Test
    void downloadPathShouldRejectInvalidUrl() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.downloadPath(new ParseRequest("ftp://example.com/file")));

        assertEquals("不支持的离线下载链接", exception.getMessage());
    }

    @Test
    void getQuotaShouldDelegateToHandler() {
        DriverAccount account = account(12, DriverType.PAN115, "3142159731515950166");
        when(settingRepository.findById("offline_download_config"))
                .thenReturn(Optional.of(new Setting("offline_download_config", "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":12,\"offlineFolderId\":\"3142159731515950166\"}")));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        when(pan115Handler.getQuota(account)).thenReturn(new OfflineDownloadHandler.QuotaResult(true, "本月配额：剩1371/总1500个"));

        OfflineDownloadQuotaResponse result = service.getQuota();

        assertTrue(result.supported());
        assertEquals("本月配额：剩1371/总1500个", result.displayText());
    }

    @Test
    void saveConfigShouldWorkWithGuangyaDriverType() {
        DriverAccount account = new DriverAccount();
        account.setId(15);
        account.setType(DriverType.GUANGYA);
        account.setName("光鸭账号");
        account.setFolder("0");
        account.setToken("test-token");
        when(driverAccountRepository.findById(15)).thenReturn(Optional.of(account));
        when(guangyaHandler.ensureOfflineFolder(account)).thenReturn("gy-folder-123");

        OfflineDownloadConfigDto response = service.saveConfig(new OfflineDownloadConfigRequest(true, "GUANGYA", 15));

        verify(settingRepository).save(any(Setting.class));
        assertEquals("GUANGYA", response.driverType());
        assertEquals(15, response.accountId());
    }

    private DriverAccount account(int id, DriverType type, String folder) {
        DriverAccount account = new DriverAccount();
        account.setId(id);
        account.setType(type);
        account.setName("😲我的115云盘");
        account.setFolder(folder);
        account.setCookie("UID=6338615_A1_1778368227; CID=test-cid; SEID=test-seid; KID=test-kid");
        return account;
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

    // ---------- submitMagnet 三态(追剧磁力兜底) ----------

    private void enableConfig(DriverAccount account) {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.of(new Setting(
                "offline_download_config",
                "{\"enabled\":true,\"driverType\":\"" + account.getType().name()
                        + "\",\"accountId\":" + account.getId() + ",\"offlineFolderId\":\"folder-1\"}")));
        when(driverAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    }

    @Test
    void submitMagnetReusesCompletedTask() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(completedTask("/pan115/alist-tvbox-offline/完成任务", "完成任务")));

        cn.har01d.alist_tvbox.model.MagnetSubmitResult result =
                service.submitMagnet("magnet:?xt=urn:btih:abc", null, null);

        assertEquals(cn.har01d.alist_tvbox.model.MagnetSubmitResult.COMPLETED, result.status());
        assertEquals("完成任务", result.taskName());
        verify(pan115Handler, never()).submitAndWait(any(), any(), any());
    }

    @Test
    void submitMagnetTreatsTimeoutAsSubmitted() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.empty());
        when(offlineDownloadTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pan115Handler.submitAndWait(any(), any(), any()))
                .thenThrow(new BadRequestException("离线下载任务未在10秒内完成"));

        cn.har01d.alist_tvbox.model.MagnetSubmitResult result =
                service.submitMagnet("magnet:?xt=urn:btih:abc", null, null);

        assertEquals(cn.har01d.alist_tvbox.model.MagnetSubmitResult.SUBMITTED, result.status());
        var captor = org.mockito.ArgumentCaptor.forClass(OfflineDownloadTask.class);
        verify(offlineDownloadTaskRepository).save(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
    }

    @Test
    void submitMagnetPendingTaskIsIdempotent() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        OfflineDownloadTask pending = new OfflineDownloadTask();
        pending.setAccountId(12);
        pending.setStatus("PENDING");
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(pending));

        cn.har01d.alist_tvbox.model.MagnetSubmitResult result =
                service.submitMagnet("magnet:?xt=urn:btih:abc", null, null);

        assertEquals(cn.har01d.alist_tvbox.model.MagnetSubmitResult.SUBMITTED, result.status());
        verify(pan115Handler, never()).submitAndWait(any(), any(), any()); // 网盘侧任务已在,不重复建
    }

    @Test
    void submitMagnetReturnsFailedOnRejection() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.empty());
        when(offlineDownloadTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pan115Handler.submitAndWait(any(), any(), any()))
                .thenThrow(new BadRequestException("task failed: 链接违规"));

        cn.har01d.alist_tvbox.model.MagnetSubmitResult result =
                service.submitMagnet("magnet:?xt=urn:btih:abc", 9, 3);

        assertEquals(cn.har01d.alist_tvbox.model.MagnetSubmitResult.FAILED, result.status());
        assertEquals("task failed: 链接违规", result.message());
        // FAILED 也落行(带订阅/集号):试错消耗配额,且同磁力不再重试
        var captor = org.mockito.ArgumentCaptor.forClass(OfflineDownloadTask.class);
        verify(offlineDownloadTaskRepository).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals(9, captor.getValue().getSubscriptionId());
        assertEquals(3, captor.getValue().getEpisode());
    }

    @Test
    void submitMagnetSkipsPreviouslyFailedMagnet() {
        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        OfflineDownloadTask failed = new OfflineDownloadTask();
        failed.setAccountId(12);
        failed.setStatus("FAILED");
        failed.setSubscriptionId(9);
        failed.setEpisode(3);
        when(offlineDownloadTaskRepository.findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(eq(12), any()))
                .thenReturn(Optional.of(failed));

        cn.har01d.alist_tvbox.model.MagnetSubmitResult result =
                service.submitMagnet("magnet:?xt=urn:btih:abc", 9, 3);

        assertEquals(cn.har01d.alist_tvbox.model.MagnetSubmitResult.FAILED, result.status());
        verify(pan115Handler, never()).submitAndWait(any(), any(), any());
    }

    @Test
    void isConfiguredReflectsSettingState() {
        when(settingRepository.findById("offline_download_config")).thenReturn(Optional.empty());
        assertFalse(service.isConfigured());

        DriverAccount account = account(12, DriverType.PAN115, "3425588780152254335");
        enableConfig(account);
        assertTrue(service.isConfigured());
        assertTrue(service.offlineRootPath().endsWith("/alist-tvbox-offline"));
    }
}
