package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.time.Instant;
import java.util.Objects;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class OfflineDownloadTask {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private Integer accountId;
    @Column(nullable = false, length = 64)
    private String urlHash;
    private String infoHash;
    @Column(columnDefinition = "TEXT")
    private String targetPath;
    private String taskName;
    private String status;
    @Column(columnDefinition = "boolean default false")
    private boolean folder;
    /** 追剧磁力兜底的提交归属(配额计数锚点);用户侧播放解析的离线为 null */
    private Integer subscriptionId;
    /** 追剧磁力兜底对应的目标集号;用户侧离线为 null */
    private Integer episode;
    private Instant createdTime;
    private Instant updatedTime;
    /** 每日离线清理的执行状态:null 未处理 / DONE 已清理(网盘侧任务已删,短路放行重提)/ FAILED 清理失败待重试 */
    @Column(name = "cleanup_state", length = 16)
    private String cleanupState;
    @Column(name = "cleanup_attempts")
    private int cleanupAttempts;
    @Column(name = "cleanup_time")
    private Instant cleanupTime;
    /** 完成/收割时间(通用入口 TTL 的起算点;PENDING 落行时为空,settle 时回填) */
    @Column(name = "completed_time")
    private Instant completedTime;
    /** 清理前固化的 115 永久分享地址(留档;播放的持久载体在资源行 link) */
    @Column(name = "share_url", length = 500)
    private String shareUrl;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        OfflineDownloadTask task = (OfflineDownloadTask) o;
        return id != null && Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
