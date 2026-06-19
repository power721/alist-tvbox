package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BackupRestoreResult {
    private int created;
    private int updated;
    private int deleted;
    private int skipped;
    private int failed;
    private List<String> errors = new ArrayList<>();
}
