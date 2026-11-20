package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginFilter;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionT4FilterTest {
    @Test
    void t4UsesExistingOrderedFilterScopeAndPreservesRuntimeMode() throws Exception {
        var service = mock(SubscriptionService.class, CALLS_REAL_METHODS);
        var repository = mock(PluginFilterRepository.class);
        var mapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        ReflectionTestUtils.setField(service, "pluginFilterRepository", repository);
        doReturn("http://127.0.0.1:4567").when(service).readHostAddress("");
        doReturn("fixture-token").when(service).getCurrentOrFirstToken();
        var plugin = new Plugin();
        plugin.setId(42);
        plugin.setName("fixture");
        plugin.setUrl("https://fixture.invalid/source.py");
        plugin.setExtend("source-data");
        var first = filter(1, "all", "", "detail", "first");
        first.setExtend("filter-data");
        var excluded = filter(2, "exclude", "42", "detail", "excluded");
        var other = filter(3, "include", "7", "detail", "other");
        var second = filter(4, "include", "42", "play,detail", "second");
        when(repository.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenReturn(List.of(first, excluded, other, second));

        for (String mode : List.of("all", "direct")) {
            var payload = mapper.readTree(Base64.getDecoder().decode(
                    service.buildT4PluginExtend(plugin, "fixture-token", mode)));
            assertEquals("source-data", payload.path("data").asText());
            assertEquals(mode.equals("direct") ? "t4-direct" : "t4", payload.path("t4_play_type").asText());
            assertFalse(payload.has("local_proxy_config"));
            assertFalse(payload.has("preheatUrl"));
            var selected = payload.path("filters");
            assertEquals(2, selected.size());
            assertEquals("first", selected.get(0).path("name").asText());
            assertEquals("filter-data", selected.get(0).path("data").asText());
            assertEquals("http://127.0.0.1:4567/plugin-filters/fixture-token/1.py?v=1",
                    selected.get(0).path("source").asText());
            assertEquals("second", selected.get(1).path("name").asText());
            assertEquals("play", selected.get(1).path("stages").get(0).asText());
        }
        when(repository.findByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        assertFalse(mapper.readTree(Base64.getDecoder().decode(
                service.buildT4PluginExtend(plugin, "fixture-token", "all"))).has("filters"));
    }

    private static PluginFilter filter(int id, String scope, String ids, String stages, String name) {
        var filter = new PluginFilter();
        filter.setId(id);
        filter.setVersion(1);
        filter.setName(name);
        filter.setPluginScope(scope);
        filter.setPluginIds(ids);
        filter.setStages(stages);
        return filter;
    }
}
