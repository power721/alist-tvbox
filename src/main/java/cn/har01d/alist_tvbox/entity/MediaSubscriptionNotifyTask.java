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
 * 追剧 Telegram 通知 outbox(借鉴 media-vault P5 publish_tasks):一条任务 = "该订阅有新闻,刷新它的 TG 卡片"。
 * <p>
 * 内容不存任务行 —— 执行时从事件流现算(天然合并同订阅多条 PENDING 为一次编辑,事件重复入队也无害);
 * 任务行只承载重试状态:发送/编辑失败按 attempts 平方退避重试,成功 SENT、超限 FAILED 留审计。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "media_subscription_notify_task")
public class MediaSubscriptionNotifyTask {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(name = "subscription_id", nullable = false)
    private int subscriptionId;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private int attempts;

    /** 下次尝试时间(epoch ms,0=立即);退避后写入,由每分钟兜底扫描捞起重试 */
    @Column(name = "next_attempt_at", nullable = false)
    private long nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError = "";

    @Column(name = "created_time", nullable = false)
    private long createdTime;

    @Column(name = "sent_time")
    private Long sentTime;
}
