package cn.har01d.alist_tvbox.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @OneToOne
    @JsonIgnore
    private User user;
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
