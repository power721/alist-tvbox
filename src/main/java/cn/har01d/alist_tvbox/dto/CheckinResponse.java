package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class CheckinResponse {
    private boolean success;
    private CheckinResult result;
}
