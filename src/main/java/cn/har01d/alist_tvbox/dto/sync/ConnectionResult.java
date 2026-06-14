package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;

@Data
public class ConnectionResult {
    private boolean success;     // 连接是否成功
    private String appVersion;   // 远端版本号
    private String token;        // 临时 token
    private String message;      // 提示信息
}
