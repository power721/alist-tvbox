package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBackupServiceTest {
    @Test
    void shouldSerializeManifestToJsonStructure() throws Exception {
        // JavaTimeModule is REQUIRED: BackupManifestDto.exportedAt defaults to Instant.now(),
        // and without the module writeValueAsString throws InvalidDefinitionException.
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setFormatVersion(1);
        manifest.setMode("repository");
        manifest.setModules(Map.of("settings", "Setting"));

        String json = mapper.writeValueAsString(manifest);

        assertThat(json).contains("\"formatVersion\":1");
        assertThat(json).contains("\"modules\":{\"settings\":\"Setting\"}");
    }
}
