package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

@Data
public class Site {
    private String name;
    private String url;
    private String password;
    private boolean searchable;
    private String searchApi = "/api/fs/search";
    private String indexFile;
}
