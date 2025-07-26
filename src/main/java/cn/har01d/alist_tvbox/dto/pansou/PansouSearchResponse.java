package cn.har01d.alist_tvbox.dto.pansou;

import lombok.Data;

@Data
public class PansouSearchResponse {
    private Integer code;
    private String message;
    private SearchResponse data;
}
