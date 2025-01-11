package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

@Data
public class HuyaResponse<T> {
    private int status;
    private T data;
    private String message;
}
