package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtvpScriptServiceTest {
    @Test
    void renderShouldInjectManagedSelfKeyringChunks() throws Exception {
        SecspiderKeyService secspiderKeyService = mock(SecspiderKeyService.class);
        when(secspiderKeyService.ensureKeyMaterial()).thenReturn(new SecspiderKeyService.KeyStatus(
                true,
                "/data/secspider/self-ed25519-private.pem",
                "/data/secspider/self-ed25519-public.pem",
                "/data/secspider/self-master-secret.txt",
                "/data/secspider/atvp-keyring.json",
                "public-key",
                List.of("pub-a", "pub-b"),
                List.of("secret-a", "secret-b")
        ));

        AtvpScriptService service = new AtvpScriptService(secspiderKeyService, new ObjectMapper());

        String script = service.render();

        assertThat(script).contains("    _self_public_key_chunks = [\"pub-a\",\"pub-b\"]");
        assertThat(script).contains("    _self_master_secret_chunks = [\"secret-a\",\"secret-b\"]");
        assertThat(script).doesNotContain("    _self_public_key_chunks = []");
        assertThat(script).doesNotContain("    _self_master_secret_chunks = []");
        assertThat(service.version()).hasSize(12);
    }
}
