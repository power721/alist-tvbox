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
 * 追剧订阅的分集实体(元数据侧):(订阅, 季, 集号) 唯一。
 * <p>
 * 特别篇 season=0、电影 S1E1(对齐 TMDB/Emby/Jellyfin 约定)。分集<b>不持有可用性</b> ——
 * 可用性由 {@link MediaSubscriptionEpisodeSource} 聚合派生(某集可播 = 存在 LISTED/VERIFIED 行)。
 * 这里只记"这一集在官方日历上是谁、何时播出"。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "msub_episode", uniqueConstraints = @UniqueConstraint(name = "uk_msub_episode", columnNames = {"subscription_id", "season", "number"}), indexes = {
        @Index(name = "idx_msub_episode_sub", columnList = "subscription_id")
})
public class MediaSubscriptionEpisode {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "subscription_id")
    private int subscriptionId;

    /** 季号:特别篇=0,电影=1 */
    @Column(nullable = false)
    private int season;

    @Column(nullable = false)
    private int number;

    @Column(length = 255)
    private String title;

    /** 播出时间(epoch ms);无日程数据为 null */
    @Column(name = "air_time")
    private Long airTime;

    /** 是否已播出(air_time 已过)。无日程数据为 null,由观测侧兜底。 */
    private Boolean aired;
}
