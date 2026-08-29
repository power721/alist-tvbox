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
    /** 归属用户:0=全局(管理员所有);>0=该用户的个人账号。普通用户创建时服务端强制覆盖为本人。 */
    private int ownerUid;
    /** 仅全局账号有效:是否允许普通用户经服务端代理使用。 */
    private boolean shared = true;
}
