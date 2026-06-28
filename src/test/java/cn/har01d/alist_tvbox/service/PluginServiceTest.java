package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private SubscriptionSourceService subscriptionSourceService;
    @Mock
    private GitHubProxyService gitHubProxyService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PluginService pluginService;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(restTemplate);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        lenient().when(subscriptionSourceService.nextSortOrder()).thenReturn(1);
        lenient().when(gitHubProxyService.readProxyListFromFile()).thenReturn(List.of());
        pluginService = new PluginService(pluginRepository, settingRepository, builder, objectMapper, new TransactionTemplate(transactionManager), subscriptionSourceService, gitHubProxyService);
    }

    @Test
    void createShouldStoreDownloadedPluginContentAndDefaultNameFromEncodedFilename() {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(pluginRepository.findByExternalId("c7070ad448464ec681f205fc849cf8a46621")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("//@id:c7070ad448464ec681f205fc849cf8a46621\n//@version:4\nplugin-body");
        when(subscriptionSourceService.nextSortOrder()).thenReturn(1);
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> {
            Plugin saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(12);
            }
            return saved;
        });

        Plugin saved = pluginService.create(plugin);

        assertThat(saved.getName()).isEqualTo("4K指南");
        assertThat(saved.getSourceName()).isEqualTo("4K指南");
        assertThat(saved.getExternalId()).isEqualTo("c7070ad448464ec681f205fc849cf8a46621");
        assertThat(saved.getContent()).isEqualTo("//@id:c7070ad448464ec681f205fc849cf8a46621\n//@version:4\nplugin-body");
        assertThat(saved.getVersion()).isEqualTo(4);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getSortOrder()).isEqualTo(1);
        assertThat(saved.getLastError()).isBlank();
        assertThat(saved.getLastCheckedAt()).isNotNull();
    }

    @Test
    void createShouldRejectUnreachableUrl() {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://example.com/missing.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenThrow(new RuntimeException("404"));

        assertThatThrownBy(() -> pluginService.create(plugin))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("插件地址不可访问");
    }

    @Test
    void refreshShouldOverwriteContentAndKeepCustomName() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("我的4K源");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4K%E6%8C%87%E5%8D%97.txt");
        plugin.setContent("old-body");
        plugin.setVersion(4);

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("//@version:5\nnew-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getName()).isEqualTo("我的4K源");
        assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
        assertThat(refreshed.getContent()).isEqualTo("//@version:5\nnew-body");
        assertThat(refreshed.getVersion()).isEqualTo(5);
        assertThat(refreshed.getLastError()).isBlank();
        assertThat(refreshed.getLastCheckedAt()).isNotNull();
    }

    @Test
    void refreshShouldKeepExistingContentWhenDownloadFails() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("4K指南");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4k.txt");
        plugin.setContent("stable-body");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenThrow(new RuntimeException("404"));
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getContent()).isEqualTo("stable-body");
        assertThat(refreshed.getLastError()).contains("插件地址不可访问");
    }

    @Test
    void updateShouldRedownloadWhenUrlChangesAndReplaceContent() {
        Plugin plugin = new Plugin();
        plugin.setId(15);
        plugin.setName("old");
        plugin.setSourceName("old");
        plugin.setUrl("https://example.com/old.txt");
        plugin.setContent("old-body");

        Plugin input = new Plugin();
        input.setName("");
        input.setUrl("https://example.com/new.txt");
        input.setEnabled(true);
        input.setExtend("token=1");

        when(pluginRepository.findById(15)).thenReturn(Optional.of(plugin));
        when(pluginRepository.findByUrl(input.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(input.getUrl()), String.class)).thenReturn("new-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin updated = pluginService.update(15, input);

        assertThat(updated.getUrl()).isEqualTo("https://example.com/new.txt");
        assertThat(updated.getSourceName()).isEqualTo("new");
        assertThat(updated.getName()).isEqualTo("new");
        assertThat(updated.getContent()).isEqualTo("new-body");
    }

    @Test
    void readContentShouldReturnStoredPluginText() {
        Plugin plugin = new Plugin();
        plugin.setId(21);
        plugin.setContent("plugin-body");

        when(pluginRepository.findById(21)).thenReturn(Optional.of(plugin));

        assertThat(pluginService.readContent(21)).isEqualTo("plugin-body");
    }

    @Test
    void deleteShouldRemovePluginRowWithoutFilesystemCleanup() {
        Plugin plugin = new Plugin();
        plugin.setId(22);

        when(pluginRepository.findById(22)).thenReturn(Optional.of(plugin));

        pluginService.delete(22);

        verify(pluginRepository).delete(plugin);
    }

    @Test
    void deleteShouldRenumberRemainingPlugins() {
        Plugin deleted = new Plugin();
        deleted.setId(22);
        deleted.setSortOrder(2);
        Plugin first = new Plugin();
        first.setId(11);
        first.setSortOrder(1);
        Plugin third = new Plugin();
        third.setId(33);
        third.setSortOrder(3);

        when(pluginRepository.findById(22)).thenReturn(Optional.of(deleted));
        pluginService.delete(22);

        assertThat(first.getSortOrder()).isEqualTo(1);
        verify(subscriptionSourceService).normalizeSortOrders();
    }

    @Test
    void deleteBatchShouldRemoveSelectedPluginsOnly() {
        Plugin first = new Plugin();
        first.setId(1);
        Plugin second = new Plugin();
        second.setId(2);

        when(pluginRepository.findAllById(List.of(1, 2))).thenReturn(List.of(first, second));

        int deleted = pluginService.deleteBatch(List.of(1, 2));

        assertThat(deleted).isEqualTo(2);
        verify(pluginRepository).deleteAll(List.of(first, second));
    }

    @Test
    void deleteBatchShouldRenumberRemainingPlugins() {
        Plugin first = new Plugin();
        first.setId(1);
        Plugin second = new Plugin();
        second.setId(2);
        Plugin remainingFirst = new Plugin();
        remainingFirst.setId(4);
        remainingFirst.setSortOrder(4);
        Plugin remainingSecond = new Plugin();
        remainingSecond.setId(6);
        remainingSecond.setSortOrder(6);

        when(pluginRepository.findAllById(List.of(1, 2))).thenReturn(List.of(first, second));
        int deleted = pluginService.deleteBatch(List.of(1, 2));

        assertThat(deleted).isEqualTo(2);
        verify(subscriptionSourceService).normalizeSortOrders();
    }

    @Test
    void reorderShouldRewriteSortOrderUsingIncomingIds() {
        Plugin first = new Plugin();
        first.setId(1);
        first.setSortOrder(1);
        Plugin second = new Plugin();
        second.setId(2);
        second.setSortOrder(2);

        when(pluginRepository.findById(2)).thenReturn(Optional.of(second));
        when(pluginRepository.findById(1)).thenReturn(Optional.of(first));
        when(pluginRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        pluginService.reorder(List.of(2, 1));

        assertThat(second.getSortOrder()).isEqualTo(1);
        assertThat(first.getSortOrder()).isEqualTo(2);
    }

    @Test
    void importFromSpidersJsonShouldRefreshExistingPluginsAndCreateNewOnes() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String firstPlugin = "https://example.com/a.txt";
        String secondPlugin = "https://example.com/b.txt";
        String payload = """
                [
                  "https://example.com/a.txt",
                  "https://example.com/a.txt",
                  "https://example.com/b.txt"
                ]
                """;
        Plugin existing = new Plugin();
        existing.setId(7);
        existing.setName("a");
        existing.setSourceName("a");
        existing.setUrl(firstPlugin);
        existing.setContent("old-a");

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByUrl(firstPlugin)).thenReturn(Optional.of(existing));
        when(pluginRepository.findByUrl(secondPlugin)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(firstPlugin), String.class)).thenReturn("new-a");
        when(restTemplate.getForObject(URI.create(secondPlugin), String.class)).thenReturn("body-b");
        when(pluginRepository.findById(7)).thenReturn(Optional.of(existing));
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.sourceUrl()).isEqualTo(sourceUrl);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.refreshedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.created()).containsExactly("b");
        assertThat(result.refreshed()).containsExactly("a");
        assertThat(result.skipped()).containsExactly(firstPlugin);
        assertThat(existing.getContent()).isEqualTo("new-a");
    }

    @Test
    void importFromRepositoryUrlShouldResolveRootSpidersJson() {
        String repositoryUrl = "https://github.com/har01d5/tvbox";
        String resolvedUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://example.com/demo.txt";

        when(restTemplate.getForObject(URI.create(resolvedUrl), String.class)).thenReturn("[\"" + pluginUrl + "\"]");
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(pluginUrl), String.class)).thenReturn("body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(repositoryUrl);

        assertThat(result.sourceUrl()).isEqualTo(resolvedUrl);
        assertThat(result.created()).containsExactly("demo");
    }

    @Test
    void createShouldUseGithubProxyForGithubUrlsWithoutChangingStoredUrl() {
        String url = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/demo.txt";
        String proxiedUrl = "https://gh-proxy.org/" + url;
        Plugin plugin = new Plugin();
        plugin.setUrl(url);

        when(settingRepository.findById("github_proxy")).thenReturn(Optional.of(new Setting("github_proxy", "https://gh-proxy.org/")));
        when(pluginRepository.findByUrl(url)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(proxiedUrl), String.class)).thenReturn("body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin saved = pluginService.create(plugin);

        assertThat(saved.getUrl()).isEqualTo(url);
        verify(restTemplate).getForObject(URI.create(proxiedUrl), String.class);
        verify(restTemplate, never()).getForObject(URI.create(url), String.class);
    }

    @Test
    void importFromStringSpidersJsonShouldResolveRelativePathsAgainstRepositoryRoot() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/demo.txt";

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn("[\"py/demo.txt\"]");
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(pluginUrl), String.class)).thenReturn("body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.created()).containsExactly("demo");
    }

    @Test
    void importFromObjectSpidersJsonShouldUseFileFieldAndResolveRelativePaths() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String relativePluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/relative.txt";
        String absolutePluginUrl = "https://example.com/absolute.txt";
        String payload = """
                [
                  {"version": 1, "file": "py/relative.txt"},
                  {"version": 2, "file": "https://example.com/absolute.txt"}
                ]
                """;

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByUrl(relativePluginUrl)).thenReturn(Optional.empty());
        when(pluginRepository.findByUrl(absolutePluginUrl)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(relativePluginUrl), String.class)).thenReturn("//@version:1\nrelative-body");
        when(restTemplate.getForObject(URI.create(absolutePluginUrl), String.class)).thenReturn("//@version:2\nabsolute-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.created()).containsExactly("relative", "absolute");
    }

    @Test
    void importFromObjectSpidersJsonShouldRefreshExistingPluginByExternalId() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String oldPluginUrl = "https://example.com/old.txt";
        String newPluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/new.txt";
        String payload = """
                [
                  {"id": "c7070ad448464ec681f205fc849cf8a46621", "version": 2, "file": "py/new.txt"}
                ]
                """;
        Plugin existing = new Plugin();
        existing.setId(17);
        existing.setExternalId("c7070ad448464ec681f205fc849cf8a46621");
        existing.setName("old");
        existing.setSourceName("old");
        existing.setUrl(oldPluginUrl);
        existing.setVersion(1);
        existing.setContent("old-body");

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByExternalId("c7070ad448464ec681f205fc849cf8a46621")).thenReturn(Optional.of(existing));
        when(pluginRepository.findById(17)).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(URI.create(newPluginUrl), String.class)).thenReturn("//@id:c7070ad448464ec681f205fc849cf8a46621\n//@version:2\nnew-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.refreshed()).containsExactly("new");
        assertThat(existing.getUrl()).isEqualTo(newPluginUrl);
        assertThat(existing.getExternalId()).isEqualTo("c7070ad448464ec681f205fc849cf8a46621");
        assertThat(existing.getContent()).contains("new-body");
    }

    @Test
    void importFromObjectSpidersJsonShouldDeduplicateEntriesByExternalId() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String firstPluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/first.txt";
        String secondPluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/second.txt";
        String payload = """
                [
                  {"id": "c7070ad448464ec681f205fc849cf8a46621", "version": 1, "file": "py/first.txt"},
                  {"id": "c7070ad448464ec681f205fc849cf8a46621", "version": 1, "file": "py/second.txt"}
                ]
                """;

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByExternalId("c7070ad448464ec681f205fc849cf8a46621")).thenReturn(Optional.empty());
        when(pluginRepository.findByUrl(firstPluginUrl)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(firstPluginUrl), String.class)).thenReturn("//@id:c7070ad448464ec681f205fc849cf8a46621\n//@version:1\nfirst-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.created()).containsExactly("first");
        assertThat(result.skipped()).containsExactly(secondPluginUrl);
        verify(restTemplate, never()).getForObject(URI.create(secondPluginUrl), String.class);
    }

    @Test
    void importFromObjectSpidersJsonShouldCreateInvalidPluginAsDisabled() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/invalid.txt";
        String payload = """
                [
                  {"version": 1, "valid": false, "file": "py/invalid.txt"}
                ]
                """;

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(pluginUrl), String.class)).thenReturn("//@version:1\ninvalid-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.created()).containsExactly("invalid");
        assertThat(result.failedCount()).isEqualTo(0);
        verify(pluginRepository).save(argThat(plugin ->
                plugin.getUrl().equals(pluginUrl)
                        && !plugin.isEnabled()
                        && Integer.valueOf(1).equals(plugin.getVersion())));
    }

    @Test
    void importFromObjectSpidersJsonShouldKeepExistingEnabledStateWhenRefreshing() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/existing.txt";
        String payload = """
                [
                  {"version": 2, "valid": false, "file": "py/existing.txt"}
                ]
                """;
        Plugin existing = new Plugin();
        existing.setId(13);
        existing.setName("existing");
        existing.setSourceName("existing");
        existing.setUrl(pluginUrl);
        existing.setVersion(1);
        existing.setEnabled(true);
        existing.setContent("old");

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.of(existing));
        when(pluginRepository.findById(13)).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(URI.create(pluginUrl), String.class)).thenReturn("//@version:2\nnew-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.refreshed()).containsExactly("existing");
        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getVersion()).isEqualTo(2);
    }

    @Test
    void importFromObjectSpidersJsonShouldSkipRefreshWhenVersionMatches() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/relative.txt";
        String payload = """
                [
                  {"version": 4, "file": "py/relative.txt"}
                ]
                """;
        Plugin existing = new Plugin();
        existing.setId(11);
        existing.setName("relative");
        existing.setSourceName("relative");
        existing.setUrl(pluginUrl);
        existing.setVersion(4);
        existing.setContent("stable");

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.of(existing));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.refreshedCount()).isEqualTo(0);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(existing.getContent()).isEqualTo("stable");
        verify(restTemplate, never()).getForObject(URI.create(pluginUrl), String.class);
    }

    @Test
    void importFromObjectSpidersJsonShouldBackfillExternalIdWhenVersionMatchesExistingUrl() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/relative.txt";
        String payload = """
                [
                  {"id": "c7070ad448464ec681f205fc849cf8a46621", "version": 4, "file": "py/relative.txt"}
                ]
                """;
        Plugin existing = new Plugin();
        existing.setId(11);
        existing.setName("relative");
        existing.setSourceName("relative");
        existing.setUrl(pluginUrl);
        existing.setVersion(4);
        existing.setContent("stable");

        when(restTemplate.getForObject(URI.create(sourceUrl), String.class)).thenReturn(payload);
        when(pluginRepository.findByExternalId("c7070ad448464ec681f205fc849cf8a46621")).thenReturn(Optional.empty());
        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.of(existing));
        when(pluginRepository.findById(11)).thenReturn(Optional.of(existing));
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(existing.getExternalId()).isEqualTo("c7070ad448464ec681f205fc849cf8a46621");
        assertThat(existing.getContent()).isEqualTo("stable");
        verify(restTemplate, never()).getForObject(URI.create(pluginUrl), String.class);
    }

    @Test
    void importFromSourceShouldRejectUnsupportedUrl() {
        assertThatThrownBy(() -> pluginService.importFromSource("https://example.com/spiders.json"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void importFromSpidersJsonShouldReportFailedRefreshes() {
        String sourceUrl = "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json";
        String pluginUrl = "https://example.com/a.txt";
        Plugin existing = new Plugin();
        existing.setId(8);
        existing.setName("a");
        existing.setSourceName("a");
        existing.setUrl(pluginUrl);
        existing.setContent("stable");

        when(pluginRepository.findByUrl(pluginUrl)).thenReturn(Optional.of(existing));
        when(pluginRepository.findById(8)).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (URI.create(sourceUrl).equals(uri)) {
                return "[\"" + pluginUrl + "\"]";
            }
            if (URI.create(pluginUrl).equals(uri)) {
                throw new RuntimeException("404");
            }
            return null;
        });
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PluginService.ImportResult result = pluginService.importFromSource(sourceUrl);

        assertThat(result.createdCount()).isEqualTo(0);
        assertThat(result.refreshedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failed().getFirst()).contains(pluginUrl);
        assertThat(existing.getContent()).isEqualTo("stable");
        assertThat(existing.getLastError()).contains("插件地址不可访问");
    }
}
