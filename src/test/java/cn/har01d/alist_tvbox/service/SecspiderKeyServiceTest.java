package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecspiderKeyServiceTest {
    @TempDir
    private Path dataDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.data.dir");
    }

    @Test
    void ensureAndResetShouldPersistContainerKeyring() throws Exception {
        System.setProperty("atv.data.dir", dataDir.toString());
        SecspiderKeyService service = new SecspiderKeyService(objectMapper);

        SecspiderKeyService.KeyStatus first = service.ensureKeyMaterial();
        SecspiderKeyService.CompilerKeyMaterial firstMaterial = service.compilerKeyMaterial();

        assertThat(first.generated()).isTrue();
        assertThat(first.publicKey()).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(first.publicKeyChunks()).isNotEmpty();
        assertThat(first.masterSecretChunks()).isNotEmpty();
        assertThat(firstMaterial.privateKey()).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(firstMaterial.masterSecret()).startsWith("self-master-");
        assertThat(Files.isRegularFile(Path.of(first.privateKeyPath()))).isTrue();
        assertThat(Files.isRegularFile(Path.of(first.keyringPath()))).isTrue();

        JsonNode keyring = objectMapper.readTree(Files.readString(Path.of(first.keyringPath()), StandardCharsets.UTF_8));
        assertThat(keyring.path("format").asText()).isEqualTo("atvp-secspider-keyring/1");
        assertThat(keyring.path("publicKeyChunks").isArray()).isTrue();
        assertThat(keyring.path("masterSecretChunks").isArray()).isTrue();

        SecspiderKeyService.KeyStatus second = service.generateKeyMaterial(true);
        SecspiderKeyService.CompilerKeyMaterial secondMaterial = service.compilerKeyMaterial();
        assertThat(second.publicKey()).isNotEqualTo(first.publicKey());
        assertThat(secondMaterial.masterSecret()).isNotEqualTo(firstMaterial.masterSecret());
    }
}
