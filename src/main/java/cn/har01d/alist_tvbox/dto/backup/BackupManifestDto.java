package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class BackupManifestDto {
    private int formatVersion = 1;
    private String appVersion;
    private Instant exportedAt = Instant.now();
    private String mode = "repository";
    private Map<String, BackupModuleDto> modules = new LinkedHashMap<>();
}
