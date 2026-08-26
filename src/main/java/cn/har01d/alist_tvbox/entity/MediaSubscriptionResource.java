package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 追剧订阅的候选资源池。同一分享按 link 去重;失效前预热多个候选,换源时直接激活次优(不必现场搜索)。
 * <p>
 * 资源只表达<b>挂载生命周期位置</b>(候选/已挂载/已退役/已拒绝),<b>不表达可用性</b> ——
 * 可用性由 {@link MediaSubscriptionEpisodeSource} 按集聚合派生(整源死 = 该资源全部行 FAILED/MISSING)。
 * 旧三标志 validity/active/gap 已废弃:主源 = 挂在订阅固定路径上的那个 MOUNTED 资源,
 * 补缺源 = 挂在 /追剧/.sources/ 下的 MOUNTED 资源,判死 = RETIRED(冷却期满可重探)。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_subscription_resource", uniqueConstraints = @UniqueConstraint(name = "uk_msub_resource", columnNames = {"subscription_id", "link_hash"}))
public class MediaSubscriptionResource {
    /** 池内未挂载,可探测/激活 */
    public static final String STATE_CANDIDATE = "CANDIDATE";
    /** 已挂载:mount_path = 订阅固定路径 → 主源;位于 /追剧/.sources/ → 补缺挂载 */
    public static final String STATE_MOUNTED = "MOUNTED";
    /** 已卸载/判死(保留行防重复入池;冷却期满 isBadCooled 允许重探) */
    public static final String STATE_RETIRED = "RETIRED";
    /** 搜索源盘检已判失效,从未获得挂载资格(保留行防重复入池) */
    public static final String STATE_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "subscription_id")
    private int subscriptionId;

    @Column(nullable = false, length = 1024)
    private String link;

    /** link 的 SHA-256 hex:(subscription_id, link_hash) 唯一索引数据源。MySQL 整列唯一索引受
     *  InnoDB 3072 字节键长限制只能建 760 字符前缀,前缀相同的长分享链会被误判重复;哈希全链唯一。
     *  算法与 V34 迁移回填一致(小写 hex,UTF-8),不可改动否则新旧行哈希口径分叉。 */
    @Column(name = "link_hash", length = 64)
    private String linkHash;

    @PrePersist
    @PreUpdate
    void refreshLinkHash() {
        this.linkHash = hashOf(link);
    }

    public static String hashOf(String link) {
        if (link == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(link.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Integer type;

    @Column(length = 16)
    private String source;

    private String title;

    @Column(length = 128)
    private String password;

    @Column(name = "episodes_found")
    private Integer episodesFound;

    private Integer score;

    @Column(nullable = false, length = 16, name = "state")
    private String state = STATE_CANDIDATE;

    @Column(name = "mount_path", length = 512)
    private String mountPath;

    @Column(name = "share_id")
    private Integer shareId;

    @Column(name = "checked_time")
    private Long checkedTime;

    @Column(name = "created_time", nullable = false)
    private long createdTime;
}
