package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class SyncResponse {
    private boolean success;  // 总体是否成功
    private Map<String, SyncResult> results = new HashMap<>();  // 各模块结果

    public void addResult(String module, SyncResult result) {
        results.put(module, result);
    }
}
