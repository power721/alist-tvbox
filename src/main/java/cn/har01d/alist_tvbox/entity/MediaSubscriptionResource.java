package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 追剧订阅的候选资源池。同一分享按 link 去重;失效前预热多个候选,换源时直接激活次优(不必现场搜索)。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_subscription_resource", uniqueConstraints = @UniqueConstraint(name = "uk_msub_resource", columnNames = {"subscription_id", "link"}))
public class MediaSubscriptionResource {
    public static final String VALIDITY_OK = "OK";
    public static final String VALIDITY_BAD = "BAD";
    public static final String VALIDITY_UNKNOWN = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "subscription_id")
    private int subscriptionId;

    @Column(nullable = false, length = 1024)
    private String link;

    private Integer type;

    @Column(length = 16)
    private String source;

    private String title;

    @Column(length = 128)
    private String password;

    @Column(name = "episodes_found")
    private Integer episodesFound;

    private Integer score;

    @Column(length = 16)
    private String validity = VALIDITY_UNKNOWN;

    private boolean active;

    /** 补缺挂载:该资源作为缺口集的附加常驻挂载(路径 mount_path,非 temp,清理豁免) */
    private boolean gap;

    @Column(name = "mount_path", length = 512)
    private String mountPath;

    @Column(name = "share_id")
    private Integer shareId;

    /** 探测/激活时解析出的集数覆盖快照(JSON 数组) */
    @Column(columnDefinition = "TEXT", name = "episode_list")
    private String episodeList;

    @Column(name = "checked_time")
    private Long checkedTime;

    @Column(name = "created_time", nullable = false)
    private long createdTime;
}
