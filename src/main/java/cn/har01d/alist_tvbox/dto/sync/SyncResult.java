package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class SyncResult {
    private int imported = 0;    // 新增数量
    private int updated = 0;     // 更新数量
    private int failed = 0;      // 失败数量
    private List<String> errors = new ArrayList<>();  // 错误信息列表
}
