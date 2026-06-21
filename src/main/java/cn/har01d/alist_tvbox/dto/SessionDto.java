package cn.har01d.alist_tvbox.dto;

import java.time.Instant;

import lombok.Data;

@Data
public class SessionDto {
    private Integer id;
    private String username;
    private String role;
    private String loginIp;
    private String userAgent;
    private String browser;
    private String os;
    private Instant loginTime;
    private Instant expireTime;
    private boolean current;
}
