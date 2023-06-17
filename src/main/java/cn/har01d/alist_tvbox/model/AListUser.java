package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class AListUser {
    private int id;
    private String username;
    private String password;
    private String base_path = "/";
    private int role = 0;
    private int permission = 368;
    private boolean disabled;
}
