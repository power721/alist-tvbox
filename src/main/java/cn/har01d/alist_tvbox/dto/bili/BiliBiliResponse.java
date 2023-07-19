package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

@Data
public class BiliBiliResponse<T> {
    private int code;
    private T data;
    private T result;
    private String message;
}
