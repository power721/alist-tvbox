package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class TokenDto {
    private boolean enabledToken;
    private String token;
    private String role;
}
