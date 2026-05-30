package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AccountInfo {
    private String id;
    private String name;
    private String cookie;
    private String token;
    private String vip = "user";
    private Map<String, Object> addition = new HashMap<>();
}
