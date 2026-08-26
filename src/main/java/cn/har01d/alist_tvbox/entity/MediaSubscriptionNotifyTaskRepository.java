package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MediaSubscriptionNotifyTaskRepository extends JpaRepository<MediaSubscriptionNotifyTask, Integer> {

    /** 兜底扫描:到期的 PENDING 任务(旧任务优先,限额防积压拖长单轮) */
    List<MediaSubscriptionNotifyTask> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedTimeAsc(String status, long cutoff);

    /** 入队去重:同订阅已有 PENDING 就不再造新任务(处理端还会按订阅合并,双保险) */
    boolean existsBySubscriptionIdAndStatus(int subscriptionId, String status);

    List<MediaSubscriptionNotifyTask> findBySubscriptionIdAndStatusOrderByIdAsc(int subscriptionId, String status);

    /** 订阅已删:回收孤儿任务(派生删除需事务,同事件表口径) */
    @Transactional
    void deleteBySubscriptionId(int subscriptionId);
}
