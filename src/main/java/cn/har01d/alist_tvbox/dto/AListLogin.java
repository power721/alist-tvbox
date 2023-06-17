package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class AListLogin {
    private boolean enabled;
    private String username;
    private String password;
}
