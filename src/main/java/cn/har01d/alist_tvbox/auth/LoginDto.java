package cn.har01d.alist_tvbox.auth;

import lombok.Data;

@Data
public class LoginDto {
    private String username;
    private String password;
    private boolean rememberMe;
}
