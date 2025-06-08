package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class AccountInfo {
    private String id;
    private String name;
    private String vip = "user";
}
