package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    private String otp_code = "";
}
