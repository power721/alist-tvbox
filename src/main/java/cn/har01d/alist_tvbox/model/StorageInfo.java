package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class StorageInfo {
    private String refreshToken;
    private String openToken;
    private String folderId;
    private String refreshTokenTime;
    private String openTokenTime;
}
