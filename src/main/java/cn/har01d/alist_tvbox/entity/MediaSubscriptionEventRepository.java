package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MediaSubscriptionEventRepository extends JpaRepository<MediaSubscriptionEvent, Integer> {
    List<MediaSubscriptionEvent> findTop100BySubscriptionIdOrderByCreatedTimeDesc(int subscriptionId);

    /** 派生删除 = 先 select 再逐个 em.remove,必须在事务里执行(无外围事务的调用方会抛 TransactionRequiredException)。 */
    @Transactional
    void deleteBySubscriptionId(int subscriptionId);

    /** 保留期清理(100+ 订阅规模):全局删除 90 天前的事件行。 */
    @Transactional
    void deleteByCreatedTimeLessThan(long cutoff);

    /** 保留期清理:取每订阅最新 201 条判定是否超额(取到 201 条才说明超过 200 保留线)。 */
    List<MediaSubscriptionEvent> findTop201BySubscriptionIdOrderByIdDesc(int subscriptionId);
}
