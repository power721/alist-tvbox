package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface OfflineDownloadTaskRepository extends JpaRepository<OfflineDownloadTask, Integer> {
    Optional<OfflineDownloadTask> findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc(Integer accountId, String urlHash);

    /** 收割结算用:该订阅该集最新一条超时 PENDING(按集回写产物名/路径,恢复 pending 闸门语义)。 */
    Optional<OfflineDownloadTask> findFirstBySubscriptionIdAndEpisodeAndStatusOrderByUpdatedTimeDesc(
            Integer subscriptionId, Integer episode, String status);

    /** 收割结算用(手动路径,集号留空):该订阅最新一条 episode=null 的超时 PENDING。 */
    Optional<OfflineDownloadTask> findFirstBySubscriptionIdAndEpisodeIsNullAndStatusOrderByUpdatedTimeDesc(
            Integer subscriptionId, String status);

    /** 该订阅是否有未收割的 PENDING 离线任务(巡检 PENDING 感知收割的判定)。 */
    boolean existsBySubscriptionIdAndStatus(Integer subscriptionId, String status);

    long countByAccountIdAndStatus(Integer accountId, String status);

    /** 单集离线配额:该订阅该集当月的提交尝试次数(含 FAILED),since=本月1号 */
    long countBySubscriptionIdAndEpisodeAndCreatedTimeGreaterThanEqual(Integer subscriptionId, Integer episode, Instant since);

    /** 单订阅离线配额(当月) */
    long countBySubscriptionIdAndCreatedTimeGreaterThanEqual(Integer subscriptionId, Instant since);

    /** 追剧总离线配额(当月;磁力兜底提交的行才带 subscription_id) */
    long countBySubscriptionIdNotNullAndCreatedTimeGreaterThanEqual(Instant since);
}
