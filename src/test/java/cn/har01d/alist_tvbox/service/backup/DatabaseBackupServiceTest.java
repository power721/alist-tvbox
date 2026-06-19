package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import cn.har01d.alist_tvbox.dto.backup.BackupModuleDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBackupServiceTest {
    @Test
    void shouldSerializeManifestToYamlStructure() throws Exception {
        // JavaTimeModule is REQUIRED: BackupManifestDto.exportedAt defaults to Instant.now(),
        // and without the module writeValueAsString throws InvalidDefinitionException.
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BackupModuleDto module = new BackupModuleDto();
        module.setEntity("Setting");
        module.setItems(List.of(Map.of("name", "api_key", "value", "abc")));

        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setFormatVersion(1);
        manifest.setMode("repository");
        manifest.setModules(Map.of("settings", module));

        String yaml = mapper.writeValueAsString(manifest);

        assertThat(yaml).contains("formatVersion: 1");
        assertThat(yaml).contains("entity: \"Setting\"");
        assertThat(yaml).contains("name: \"api_key\"");
    }
}
