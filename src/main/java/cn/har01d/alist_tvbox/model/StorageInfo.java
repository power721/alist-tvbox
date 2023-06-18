package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class StorageInfo {
    private String accessToken;
    private String openToken;
    private String folderId;
    private String accessTokenTime;
    private String openTokenTime;
}
