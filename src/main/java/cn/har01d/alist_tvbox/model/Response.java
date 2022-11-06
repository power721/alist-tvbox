package cn.har01d.alist_tvbox.model;

import lombok.Data;

@Data
public class Response<T> {
    private Integer code;
    private String message;
    private T data;
}
