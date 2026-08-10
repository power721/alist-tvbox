package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class PlaybackTombstone {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false)
    private int uid;
    // sync_scope:删除事件所属订阅分区(空=uid 级/管理端全局);与 scope(删除广度)是两个轴
    private String syncScope;
    private String scope;
    private String sourceKind;
    private String sourceKey;
    // 与 history.vod_id 对齐:身份列不可截断,长 id 必须原样存
    @Column(columnDefinition = "TEXT")
    private String vodId;
    @Column(columnDefinition = "TEXT")
    private String historyKey;
    @Column(nullable = false)
    private long deletedAt;
    private Long changeSeq;
    @Column(nullable = false)
    private long expireAt;
}
