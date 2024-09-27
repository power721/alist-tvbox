package cn.har01d.alist_tvbox.drivers;

import lombok.Data;

@Data
public class AlDriverConfig {
    private String accessToken;
    private String refreshToken;
    private String driveId;
}
