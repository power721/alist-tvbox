package cn.har01d.alist_tvbox.dto;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

@Data
@ToString(exclude = {"accessToken", "refreshToken", "openToken", "openAccessToken"})
public class AccountDto {
    private String nickname;
    private String refreshToken = "";
    private Instant refreshTokenTime;
    private String accessToken = "";
    private Instant accessTokenTime;
    private String openToken = "";
    private Instant openTokenTime;
    private String openAccessToken = "";
    private Instant openAccessTokenTime;
    private Instant checkinTime;
    private int checkinDays;
    private boolean autoCheckin;
    private boolean showMyAli;
    private boolean useProxy;
    private boolean master;
    private boolean clean;
    private Integer concurrency = 4;
}
