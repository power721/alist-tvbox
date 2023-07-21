package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

@Data
public class QrCodeResult {
    private String url;
    private String refresh_token;
    private String message;
    private long timestamp;
    private int code;
}
