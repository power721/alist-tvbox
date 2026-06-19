package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class BackupRestoreResponse {
    private boolean success;
    private Map<String, BackupRestoreResult> results = new LinkedHashMap<>();
}
