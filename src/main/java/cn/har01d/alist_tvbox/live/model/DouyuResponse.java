package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

@Data
public class DouyuResponse<T> {
    private int code;
    private T data;
}
