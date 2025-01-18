package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

@Data
public class BilibiliResponse<T> {
    private int code;
    private T data;
    private String message;
}
