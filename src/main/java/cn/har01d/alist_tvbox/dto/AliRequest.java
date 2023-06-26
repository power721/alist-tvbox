package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AliRequest {
    private Map<String, String> body = new HashMap<>();
    private Map<String, String> headers = new HashMap<>();
    private String id;
    private String method = "POST";
    private String url = "/file/delete";

    public AliRequest() {
        headers.put("Content-Type", "application/json");
    }
}
