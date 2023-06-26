package cn.har01d.alist_tvbox.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToOne;
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
    private String openToken = "";
    private Instant openTokenTime;
    private String folderId = "";
    private Instant checkinTime;
    private int checkinDays;
    private boolean autoCheckin;
    private boolean showMyAli;
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean master;
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean clean;
}
