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

    /** 收割归属对账用:该订阅全部 PENDING 行(按集号/预测产物名匹配未知产物)。 */
    java.util.List<OfflineDownloadTask> findBySubscriptionIdAndStatus(Integer subscriptionId, String status);

    /** 手动 PENDING 行按预测产物名结算(精确匹配优先)。 */
    Optional<OfflineDownloadTask> findFirstBySubscriptionIdAndEpisodeIsNullAndStatusAndTaskName(
            Integer subscriptionId, String status, String taskName);

    /** 手动 PENDING 行(无预测名)结算的近似回落。 */
    Optional<OfflineDownloadTask> findFirstBySubscriptionIdAndEpisodeIsNullAndStatusAndTaskNameIsNull(
            Integer subscriptionId, String status);

    /** 手动 PENDING 行结算的前缀容错匹配(网盘产物名与 dn/ed2k 名可能有一方带前后缀)。 */
    @org.springframework.data.jpa.repository.Query("select t from OfflineDownloadTask t"
            + " where t.subscriptionId = :sid and t.episode is null and t.status = :status"
            + " and t.taskName is not null and (t.taskName like concat(:name, '%') or :name like concat(t.taskName, '%'))"
            + " order by t.updatedTime desc")
    Optional<OfflineDownloadTask> findFirstManualPendingByNameLenient(Integer sid, String status, String name);

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
