package cn.har01d.alist_tvbox.auth;

import lombok.Data;

import java.time.Instant;

@Data
public class Token {
    private String username;
    private String token;
    private Instant activeTime;
}
