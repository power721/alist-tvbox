package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCredentialRedirectTest {
    @Test
    void redirectTo401RequiresCredentialsButRedirectTo403DoesNot() throws Exception {
        var mapper = new ObjectMapper();
        for (int status : List.of(401, 403)) {
            var settings = mock(ConnectionSettingsStore.class);
            var saved = new AtomicReference<>(mapper.createObjectNode());
            when(settings.sourceCredentials("source")).thenAnswer(ignored -> saved.get().deepCopy());
            doAnswer(call -> {
                saved.set(((ObjectNode) call.getArgument(1)).deepCopy());
                return null;
            }).when(settings).saveSourceCredentials(eq("source"), any());
            var credentials = new SourceCredentialStore(settings, mapper);
            var evidence = mapper.createObjectNode();
            var http = evidence.putArray("httpObservations");
            http.addObject().put("responseComplete", true).put("responseStatus", 301);
            http.addObject().put("responseComplete", true).put("responseStatus", status);
            credentials.observe("source", "", "a".repeat(64), evidence);
            assertEquals(status == 401 ? "missing" : "optional", credentials.summary("source").status());
            assertEquals(status == 401 ? "SOURCE_CREDENTIALS_REQUIRED" : null,
                    credentials.blockedReason("source"));
            assertEquals("", credentials.revision("source"));
            assertFalse(credentials.summary("source").configured());
        }
    }
}
