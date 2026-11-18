package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtHostAccessTest {
    @Test
    void emptyConnectionUsesHostWithoutAddressOrCredentialAndExclusionsStillApply() {
        var mapper = new ObjectMapper();
        var client = new AtReadClient(mapper, new AtConnectionSettings("", "", "", "session", "", false, ""));
        var host = mock(AtHostAccess.class);
        var connection = new AtConnection(true, "current-at", "a".repeat(64), "b".repeat(64), "host", "at_embedded_repository");
        var plugin = new AtPluginSummary(7, "host", null, true, true, 1, "python", "plugin-7", "c".repeat(64), false);
        when(host.connection()).thenReturn(connection);
        when(host.plugins()).thenReturn(List.of(plugin));
        byte[] body = "raise RuntimeError('never execute')".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(host.readPythonSource(7)).thenReturn(body);
        client.hostAccess(host);
        assertTrue(client.usingEmbedded());
        assertEquals(connection, client.connection());
        var observation = client.observe();
        assertArrayEquals(body, client.readPythonSource(observation.sha256(), 7));
        assertEquals("at_embedded_source_read", client.sourceReadAuthority(7));
        client.configureExclusions("7");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> client.readPythonSource(observation.sha256(), 7));
        verify(host, times(1)).readPythonSource(7);
    }

    @Test
    void switchingBackToHostPreservesSavedRemoteSettingsAndInvalidatesObservations() {
        var mapper = new ObjectMapper();
        var settings = new AtConnectionSettings("http://127.0.0.1:4567", "external-at", "test", "api_key",
                "private-at-key", true, "", true);
        var client = new AtReadClient(mapper, settings);
        var host = mock(AtHostAccess.class);
        when(host.connection()).thenReturn(new AtConnection(true, "current-at", "host-key", "scope", "host", "host"));
        when(host.plugins()).thenReturn(List.of());
        client.hostAccess(host);
        var observation = client.observe();
        var remote = new AtReadClient(mapper, new AtConnectionSettings(settings.origin(), settings.instanceId(),
                settings.revision(), settings.authMode(), settings.credential(), true, "", false));
        client.replace(remote);
        assertFalse(client.usingEmbedded());
        assertEquals("external-at", client.connection().instanceId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> client.requireObservation(observation.sha256()));
        client.replace(new AtReadClient(mapper, settings));
        assertTrue(client.usingEmbedded());
        assertEquals("current-at", client.connection().instanceId());
        client.close();
    }
}
