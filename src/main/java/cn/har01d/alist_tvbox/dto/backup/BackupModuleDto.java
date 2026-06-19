package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class BackupModuleDto {
    private String entity;
    private List<Map<String, Object>> items = new ArrayList<>();
}
