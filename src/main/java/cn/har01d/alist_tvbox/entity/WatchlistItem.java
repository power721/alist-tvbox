package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 稍后再看条目(用户级想看队列,docs/watchlist-design.md)。
 * <p>
 * 纯静态标记,无后台巡检:想要「更新了提醒我」走转订阅(PianDanSubscriptionService.subscribe)。
 * vodId 为片单形态(tmdb:tv:123 / db:456 / s:{标题}[@{年份}]),详情与转订阅复用片单链路。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@Table(name = "watchlist_item", uniqueConstraints = @UniqueConstraint(name = "uk_watchlist_item", columnNames = {"uid", "vod_id"}))
public class WatchlistItem {
    /** 队列态(默认):想看未看。 */
    public static final String STATUS_WANT = "WANT";
    /** 已看完归档(二期 UI)。 */
    public static final String STATUS_WATCHED = "WATCHED";
    /** 收藏:长期留档(二期 UI)。 */
    public static final String STATUS_FAVORITE = "FAVORITE";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false)
    private int uid;

    @Column(nullable = false, name = "vod_id", length = 250)
    private String vodId;

    @Column(nullable = false, length = 16)
    private String status = STATUS_WANT;

    @Column(nullable = false, length = 250)
    private String title;

    /** H2/PG 保留字,引号小写列名(同 Movie.year 形态)。 */
    @Column(name = "\"year\"")
    private Integer year;

    /** 多季剧条目标记的季号(转订阅时透传);null = 未标季。 */
    private Integer season;

    /** 封面快照:列表页自给自足,不依赖 TMDB 详情缓存。 */
    @Column(length = 512)
    private String pic;

    /** 评分等快照(vod_remarks 原文,角标由展示层拼)。 */
    @Column(length = 250)
    private String remarks;

    private long createdTime;

    /** 状态流转时间(WATCHED/FAVORITE 归档排序用)。 */
    private Long statusTime;
}
