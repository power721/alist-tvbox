package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 采集源兜底补集行:候选源(转存/主源/补缺)全灭时,播放链路最后一级从 MacCMS 采集站
 * 搜回的某集直链。独立的「播放覆盖层」—— <b>不参与</b> msub_episode_source 状态机,
 * 不改写追剧原始数据;原始源恢复后 playCandidates 自然夺回优先级,本行到 expires_at 自然淘汰。
 * <p>
 * 行同时持有资源标识(siteId+resourceId)与直链缓存(url):播放期先字节级探测缓存直链,
 * 死了才回采集站重解析 —— 快路径 0 额外请求,慢路径与「每次重解析」等价。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "msub_episode_fallback", uniqueConstraints = @UniqueConstraint(name = "uk_msub_episode_fallback", columnNames = {"subscription_id", "episode"}), indexes = {
        @Index(name = "idx_msub_ef_sub", columnList = "subscription_id")
})
public class MediaSubscriptionEpisodeFallback {
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "subscription_id")
    private int subscriptionId;

    /** 集号(与 msub_episode.number 同口径的规范化编号) */
    @Column(nullable = false)
    private int episode;

    /** 采集站标识(feifan/wolong/…,资源聚合.py SITES 前 8) */
    @Column(nullable = false, length = 16, name = "site_id")
    private String siteId;

    /** 采集站内的 vod_id(回站重解析的凭据) */
    @Column(nullable = false, length = 64, name = "resource_id")
    private String resourceId;

    /** 播放线路名(详情页 vod_play_from 组名,仅展示用) */
    @Column(length = 128)
    private String line;

    /** 集标题(采集站返回的原始名,如「第10集」) */
    @Column(length = 255)
    private String title;

    /** 直链缓存(m3u8/mp4):播放期先探测此值,死了用 siteId+resourceId 重解析重建 */
    @Column(length = 1024)
    private String url;

    @Column(nullable = false, length = 16)
    private String state = STATE_ACTIVE;

    /** 最近一次字节级预检通过时间(epoch ms) */
    @Column(name = "validated_at")
    private Long validatedAt;

    /** 行过期时间(epoch ms):过期即出局重新采集,永不续期 */
    @Column(name = "expires_at")
    private Long expiresAt;
}
