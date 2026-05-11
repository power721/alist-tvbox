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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    void createShouldDefaultNameFromEncodedFilename() {
        Plugin plugin = new Plugin();
        plugin.setUrl("https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt");

        when(pluginRepository.findByUrl(plugin.getUrl())).thenReturn(Optional.empty());
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("#EXTM3U");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin saved = pluginService.create(plugin);

        assertThat(saved.getName()).isEqualTo("4K指南");
        assertThat(saved.getSourceName()).isEqualTo("4K指南");
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
    void refreshShouldPreserveCustomNameAndUpdateSourceName() {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName("我的4K源");
        plugin.setSourceName("4K指南");
        plugin.setUrl("https://example.com/4K%E6%8C%87%E5%8D%97.txt");

        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        when(restTemplate.getForObject(URI.create(plugin.getUrl()), String.class)).thenReturn("ok");
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Plugin refreshed = pluginService.refresh(9);

        assertThat(refreshed.getName()).isEqualTo("我的4K源");
        assertThat(refreshed.getSourceName()).isEqualTo("4K指南");
        assertThat(refreshed.getLastError()).isBlank();
        assertThat(refreshed.getLastCheckedAt()).isNotNull();
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
