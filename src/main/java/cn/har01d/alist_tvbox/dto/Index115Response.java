package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class Index115Response<T> {
    private int code;
    private String message;
    private T data;
}
