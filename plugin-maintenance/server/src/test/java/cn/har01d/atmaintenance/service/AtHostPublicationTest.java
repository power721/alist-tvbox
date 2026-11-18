package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtHostPublicationTest {
    @Test
    void hostSessionUsesSameIdentityAndDispatchesWithoutHttpCredentials() {
        var settings = mock(ConnectionSettingsStore.class);
        when(settings.at()).thenReturn(new AtConnectionSettings("", "", "", "session", "", false, ""));
        var mapper = new ObjectMapper();
        var host = mock(AtHostPublication.class);
        when(host.instanceKey()).thenReturn("host-instance");
        var plugin = mapper.createObjectNode().put("id", 7).put("url", "/static/plugins/sample.py");
        var plugins = mapper.createArrayNode().add(plugin);
        when(host.plugins()).thenReturn(plugins);
        byte[] bytes = "# source is data, never executed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(host.source("/static/plugins/sample.py", false)).thenReturn(bytes);
        var client = new AtPluginPublishClient(settings, mapper);
        ReflectionTestUtils.setField(client, "host", host);
        var activation = new StartAtRuntimeActivationRequest("a".repeat(64), "b".repeat(64), null, "test", false);
        try (var session = client.open()) {
            assertEquals("host-instance", session.instanceKey());
            assertSame(plugins, session.plugins());
            assertEquals("/static/plugins/sample.py", session.customPath(plugin));
            assertArrayEquals(bytes, session.source("/static/plugins/sample.py", false));
            session.upload("/static/plugins/sample.py", bytes);
            session.refresh(7);
            session.rename(plugin, "sample-v2", plugin);
            session.savedRuntime(7);
            session.activate(7, activation, null);
        }
        verify(host).upload("/static/plugins/sample.py", bytes);
        verify(host).refresh(7);
        verify(host).rename(7, "sample-v2", plugin);
        verify(host).savedRuntime(7);
        verify(host).activate(7, activation, null);
    }

    @Test
    void explicitExternalSelectionDoesNotDispatchToHost() {
        var settings = mock(ConnectionSettingsStore.class);
        when(settings.at()).thenReturn(new AtConnectionSettings("https://external.example", "external", "test",
                "api_key", "", false, "", false));
        var host = mock(AtHostPublication.class);
        var client = new AtPluginPublishClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "host", host);
        try (var session = client.open()) {
            assertEquals(Hashes.sha256("external\n" + Hashes.sha256("https://external.example/")), session.instanceKey());
        }
        verifyNoInteractions(host);
    }
}
