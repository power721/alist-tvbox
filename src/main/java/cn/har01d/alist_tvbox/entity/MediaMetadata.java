package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 媒体元数据持久层:provider+条目+季 → 详情 JSON 快照。
 * provider 内存缓存(6h)重启即空,此表让完结剧永久零网络、在播剧按 TTL 刷新;
 * 媒体详情页(分集标题/播出时间/简介)也由此供给,不再实时查外部接口。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_metadata", indexes = {
        @Index(name = "idx_media_meta_key", columnList = "provider, meta_id, season", unique = true)
})
public class MediaMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "meta_id", nullable = false, length = 64)
    private String metaId;

    @Column(nullable = false)
    private int season;

    /** RETURNING/ENDED/UNKNOWN:RETURNING 按 TTL 重刷,其余视为稳定不再请求 */
    @Column(nullable = false, length = 16)
    private String status;

    /** MetadataDetails 完整 JSON(名称/封面/年份/简介/别名/分集/日程) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "fetch_time", nullable = false)
    private long fetchTime;
}
