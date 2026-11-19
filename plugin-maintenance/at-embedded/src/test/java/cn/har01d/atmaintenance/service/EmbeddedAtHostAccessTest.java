package cn.har01d.atmaintenance.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddedAtHostAccessTest {
    @Test
    void savedEncryptedContentIsReadWithoutFetchingOrExecutingIt() {
        var repository = mock(PluginRepository.class);
        @SuppressWarnings("unchecked")
        var runtime = (ObjectProvider<EmbeddedAtRuntimeService>) mock(ObjectProvider.class);
        var host = new EmbeddedAtHostAccess(repository, runtime);
        var plugin = new Plugin();
        plugin.setUrl("https://fixture.invalid/never-fetch.TXT?revision=1");
        plugin.setContent("//@format:secspider/1\npayload.base64:opaque\n");
        when(repository.findById(7)).thenReturn(Optional.of(plugin));
        assertArrayEquals(plugin.getContent().getBytes(StandardCharsets.UTF_8), host.readPythonSource(7));
        verifyNoInteractions(runtime);
        plugin.setEnabled(false);
        assertEquals("AT_SOURCE_NOT_READABLE", assertThrows(ResponseStatusException.class,
                () -> host.readPythonSource(7)).getReason());
    }
}
