package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;
import java.util.List;

@Data
public class SyncRequest {
    private String remoteUrl;           // 远端地址
    private String username;            // 用户名
    private String password;            // 密码
    private List<String> modules;       // 要同步的模块
    private MergeStrategy strategy;     // 合并策略（仅拉取时用）
    private boolean force;              // 是否强制同步（版本不匹配时）
    private SyncData data;              // 同步数据（仅导入时用）
}
