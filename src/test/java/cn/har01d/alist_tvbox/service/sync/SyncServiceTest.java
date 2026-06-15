package cn.har01d.alist_tvbox.service.sync;

import cn.har01d.alist_tvbox.dto.sync.*;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.exception.VersionMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private ShareRepository shareRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private PikPakAccountRepository pikPakAccountRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private PluginFilterRepository pluginFilterRepository;
    @Mock
    private JellyfinRepository jellyfinRepository;
    @Mock
    private EmbyRepository embyRepository;
    @Mock
    private FeiniuRepository feiniuRepository;
    @Mock
    private RemoteClient remoteClient;
    @Mock
    private cn.har01d.alist_tvbox.service.UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private SyncService syncService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 使用反射设置 objectMapper
        try {
            var field = SyncService.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(syncService, objectMapper);
        } catch (Exception e) {
            fail("Failed to inject objectMapper: " + e.getMessage());
        }
    }

    @Test
    void testImportData_UseTypedGetters() {
        // Given
        Setting versionSetting = new Setting("app_version", "1.0");
        when(settingRepository.findById("app_version")).thenReturn(Optional.of(versionSetting));

        SyncData data = new SyncData();
        data.setAppVersion("1.0");

        // 使用类型化 setter
        Map<String, String> settings = new HashMap<>();
        settings.put("test_key", "test_value");
        data.setSettings(settings);

        List<Site> sites = new ArrayList<>();
        Site site = new Site();
        site.setUrl("http://test.com");
        sites.add(site);
        data.setSites(sites);

        when(siteRepository.findByUrl(anyString())).thenReturn(Optional.empty());
        when(siteRepository.save(any(Site.class))).thenReturn(site);

        // When
        Map<String, SyncResult> results = syncService.importData(data, MergeStrategy.MERGE, false);

        // Then
        assertNotNull(results);
        assertTrue(results.containsKey("settings"));
        assertTrue(results.containsKey("sites"));
        assertEquals(1, results.get("sites").getImported());
    }

    @Test
    void testImportData_VersionMismatch_ThrowsException() {
        // Given
        Setting versionSetting = new Setting("app_version", "1.0");
        when(settingRepository.findById("app_version")).thenReturn(Optional.of(versionSetting));

        SyncData data = new SyncData();
        data.setAppVersion("2.0");

        // When & Then
        VersionMismatchException exception = assertThrows(
            VersionMismatchException.class,
            () -> syncService.importData(data, MergeStrategy.MERGE, false)
        );

        assertEquals("1.0", exception.getLocalVersion());
        assertEquals("2.0", exception.getRemoteVersion());
    }

    @Test
    void testImportShares_Synchronized_NoRaceCondition() throws InterruptedException {
        // Given
        Share share1 = new Share();
        share1.setType(1);
        share1.setShareId("share1");

        Share share2 = new Share();
        share2.setType(1);
        share2.setShareId("share2");

        when(shareRepository.findByTypeAndShareId(anyInt(), anyString())).thenReturn(Optional.empty());
        when(shareRepository.findAll()).thenReturn(new ArrayList<>());
        when(shareRepository.save(any(Share.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When - 并发导入
        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> importedCounts = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                SyncResult result = syncService.importShares(List.of(share1), MergeStrategy.MERGE);
                importedCounts.add(result.getImported());
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                SyncResult result = syncService.importShares(List.of(share2), MergeStrategy.MERGE);
                importedCounts.add(result.getImported());
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Then - 验证两次导入都成功
        assertEquals(2, importedCounts.size());
        assertEquals(1, importedCounts.get(0));
        assertEquals(1, importedCounts.get(1));
    }

    @Test
    void testPush_WithVersionCheck() throws Exception {
        // Given
        String remoteUrl = "http://remote:4567";
        String username = "admin";
        String password = "password";
        String token = "test-token";

        Setting versionSetting = new Setting("app_version", "1.0");
        when(settingRepository.findById("app_version")).thenReturn(Optional.of(versionSetting));

        when(remoteClient.login(remoteUrl, username, password)).thenReturn(token);

        SyncData remoteData = new SyncData();
        remoteData.setAppVersion("2.0");  // 版本不匹配
        when(remoteClient.fetchRemoteData(eq(remoteUrl), eq(token), anyList())).thenReturn(remoteData);

        // When & Then
        assertThrows(
            VersionMismatchException.class,
            () -> syncService.push(remoteUrl, username, password, List.of("sites"), false)
        );

        verify(remoteClient).login(remoteUrl, username, password);
        verify(remoteClient).fetchRemoteData(eq(remoteUrl), eq(token), anyList());
        verify(remoteClient).logout(remoteUrl, token);  // 确保清理会话
    }

    @Test
    void testPush_ForceSync_BypassVersionCheck() throws Exception {
        // Given
        String remoteUrl = "http://remote:4567";
        String username = "admin";
        String password = "password";
        String token = "test-token";

        Setting versionSetting = new Setting("app_version", "1.0");
        when(settingRepository.findById("app_version")).thenReturn(Optional.of(versionSetting));

        when(remoteClient.login(remoteUrl, username, password)).thenReturn(token);

        SyncData remoteData = new SyncData();
        remoteData.setAppVersion("2.0");  // 版本不匹配
        when(remoteClient.fetchRemoteData(eq(remoteUrl), eq(token), anyList())).thenReturn(remoteData);

        when(siteRepository.findAll()).thenReturn(new ArrayList<>());

        Map<String, SyncResult> pushResults = new HashMap<>();
        pushResults.put("sites", new SyncResult());
        when(remoteClient.pushToRemote(eq(remoteUrl), eq(token), any(SyncData.class), eq("OVERWRITE"), eq(true)))
            .thenReturn(pushResults);

        // When - 强制同步
        SyncResponse response = syncService.push(remoteUrl, username, password, List.of("sites"), true);

        // Then
        assertTrue(response.isSuccess());
        verify(remoteClient).pushToRemote(eq(remoteUrl), eq(token), any(SyncData.class), eq("OVERWRITE"), eq(true));
    }

    @Test
    void testExportSettings_OnlyWhitelistedKeys() {
        // Given
        Setting versionSetting = new Setting("app_version", "1.0");
        Setting allowedSetting = new Setting("bilibili_cookie", "test_cookie");

        when(settingRepository.findById("app_version")).thenReturn(Optional.of(versionSetting));
        when(settingRepository.findById("bilibili_cookie")).thenReturn(Optional.of(allowedSetting));
        // 其他白名单 key 返回 empty
        when(settingRepository.findById(argThat(key ->
            !key.equals("app_version") && !key.equals("bilibili_cookie")
        ))).thenReturn(Optional.empty());

        // When
        SyncData data = syncService.exportData(List.of("settings"));

        // Then
        Map<String, String> settings = data.getSettings();
        assertNotNull(settings);
        assertEquals(1, settings.size());  // 只有一个 key
        assertTrue(settings.containsKey("bilibili_cookie"));
        assertEquals("test_cookie", settings.get("bilibili_cookie"));
    }

    @Test
    void testImportSites_OverwritePreservesBuiltInAListSite() {
        // Given
        Site builtIn = site(1, "AList", "http://127.0.0.1:5244");
        Site oldSite = site(2, "Old", "http://old.example.com");
        Site remoteBuiltIn = site(1, "Remote AList", "http://127.0.0.1:5244");
        Site remoteSite = site(9, "Remote", "http://remote.example.com");

        when(siteRepository.findAll()).thenReturn(List.of(builtIn, oldSite));
        when(siteRepository.findByUrl(remoteSite.getUrl())).thenReturn(Optional.empty());
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        SyncResult result = syncService.importSites(List.of(remoteBuiltIn, remoteSite), MergeStrategy.OVERWRITE);

        // Then
        verify(siteRepository, never()).deleteAll();
        verify(siteRepository).deleteAll(List.of(oldSite));
        verify(siteRepository, never()).findByUrl(remoteBuiltIn.getUrl());
        assertEquals(1, result.getImported());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getFailed());
    }

    @Test
    void testImportSites_DoesNotUpdateLocalBuiltInAListSiteMatchedByUrl() {
        // Given
        Site builtIn = site(1, "AList", "http://127.0.0.1:5244");
        Site remote = site(9, "Remote AList", "http://127.0.0.1:5244");

        when(siteRepository.findByUrl(remote.getUrl())).thenReturn(Optional.of(builtIn));

        // When
        SyncResult result = syncService.importSites(List.of(remote), MergeStrategy.MERGE);

        // Then
        verify(siteRepository, never()).save(any(Site.class));
        assertEquals("AList", builtIn.getName());
        assertEquals(0, result.getImported());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getFailed());
    }

    private Site site(Integer id, String name, String url) {
        Site site = new Site();
        site.setId(id);
        site.setName(name);
        site.setUrl(url);
        return site;
    }
}
