package cn.har01d.alist_tvbox.dto.sync;

import lombok.Data;

@Data
public class ConnectionInfo {
    private String url;       // 远端地址
    private String username;  // 用户名
    private String password;  // 密码
}
