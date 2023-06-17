package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class ShareInfo {
    private Integer id;
    private String path;
    private String shareId;
    private String folderId;
    private String status;
    private String password;
}
