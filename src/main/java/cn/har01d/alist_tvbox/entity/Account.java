package cn.har01d.alist_tvbox.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@Setter
@ToString(exclude = {"accessToken", "refreshToken"})
@RequiredArgsConstructor
@Entity
@Table(indexes = {
    @Index(name = "idx_account_nickname", columnList = "nickname")
})
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String nickname;
    private String refreshToken = "";
    private Instant refreshTokenTime;
    @Column(columnDefinition = "TEXT")
    private String accessToken = "";
    private Instant accessTokenTime;
    @Column(columnDefinition = "TEXT")
    private String openToken = "";
    private Instant openTokenTime;
    @Column(columnDefinition = "TEXT")
    private String openAccessToken = "";
    private Instant openAccessTokenTime;
    private Instant checkinTime;
    private int checkinDays;
    private boolean autoCheckin;
    private boolean showMyAli;
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean master;
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean clean;
    @Column(columnDefinition = "BOOLEAN DEFAULT false")
    private boolean useProxy;
    private Integer concurrency = 4;
    private Integer chunkSize = 256;
}
