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

/**
 * 追剧订阅事件流(站内通知):新集/失效/换源/错误等,前端小红点与时间线由此驱动。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_subscription_event")
public class MediaSubscriptionEvent {
    public static final String TYPE_NEW_EPISODE = "NEW_EPISODE";
    public static final String TYPE_SOURCE_INVALID = "SOURCE_INVALID";
    public static final String TYPE_SOURCE_REPLACED = "SOURCE_REPLACED";
    public static final String TYPE_GAP_FILLED = "GAP_FILLED";
    public static final String TYPE_DRIVE_LINE = "DRIVE_LINE";
    public static final String TYPE_POOL_FILLED = "POOL_FILLED";
    public static final String TYPE_TRANSFER_DONE = "TRANSFER_DONE";
    public static final String TYPE_TRANSFER_FAILED = "TRANSFER_FAILED";
    public static final String TYPE_UPGRADE_AVAILABLE = "UPGRADE_AVAILABLE";
    public static final String TYPE_ARCHIVED = "ARCHIVED";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_ENDED = "ENDED";
    public static final String TYPE_RESUMED = "RESUMED";
    /** 详情页"检查更新"(轻量):刷新元数据后官方已播 vs 本地已有的结论,不含资源搜索/挂载 */
    public static final String TYPE_UPDATE_CHECK = "UPDATE_CHECK";
    /** 用户钉选/取消钉选主源(用户自发动作,只进事件流不外发通知) */
    public static final String TYPE_PINNED = "PINNED";
    /** 磁力兜底已提交离线下载(网盘侧任务建立,产物落地后由下轮巡检收割入库;只进事件流) */
    public static final String TYPE_MAGNET_SUBMITTED = "MAGNET_SUBMITTED";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "subscription_id")
    private int subscriptionId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_time", nullable = false)
    private long createdTime;
}
