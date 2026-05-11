package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder builder;

    private PluginService pluginService;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(restTemplate);
        pluginService = new PluginService(pluginRepository, builder);
    }

    @Test
    void createShouldStoreDownloadedPluginContentAndDefaultNameFromEncodedFilename() {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("plugin-body");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
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
        assertThat(saved.getContent()).isEqualTo("plugin-body");
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

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("new-body");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getName()).isEqualTo("我的4K源");
        assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
        assertThat(refreshed.getContent()).isEqualTo("new-body");
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
}
