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

class SelfPluginFileServiceTest {
    @TempDir
    private Path webDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.web.dir");
    }

    @Test
    void storeShouldWritePackageAndUpsertRepositoryIndex() throws Exception {
        System.setProperty("atv.web.dir", webDir.toString());
        SelfPluginFileService service = new SelfPluginFileService(objectMapper);

        SelfPluginFileService.StoredPlugin stored = service.store(
                "javbus_self",
                3,
                "//@name:JavBus\npayload.base64:test\n",
                "http://localhost:4567"
        );
        service.store("javbus_self", 4, "updated", "http://localhost:4567");

        Path packagePath = webDir.resolve("static/self-plugins/py/javbus_self.txt");
        Path indexPath = webDir.resolve("static/self-plugins/spiders_v2.json");
        assertThat(Files.readString(packagePath, StandardCharsets.UTF_8)).isEqualTo("updated");
        assertThat(stored.pluginUrl()).isEqualTo("http://localhost:4567/static/self-plugins/py/javbus_self.txt");
        assertThat(stored.repositoryUrl()).isEqualTo("http://localhost:4567/static/self-plugins/spiders_v2.json");

        JsonNode index = objectMapper.readTree(Files.readString(indexPath, StandardCharsets.UTF_8));
        assertThat(index).hasSize(1);
        assertThat(index.get(0).path("id").asText()).isEqualTo("javbus_self");
        assertThat(index.get(0).path("file").asText()).isEqualTo("py/javbus_self.txt");
        assertThat(index.get(0).path("version").asInt()).isEqualTo(4);
        assertThat(index.get(0).path("valid").asBoolean()).isTrue();
    }
}
