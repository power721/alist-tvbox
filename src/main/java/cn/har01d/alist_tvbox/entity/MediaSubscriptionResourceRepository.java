package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionResourceRepository extends JpaRepository<MediaSubscriptionResource, Integer> {
    List<MediaSubscriptionResource> findBySubscriptionIdOrderByScoreDesc(int subscriptionId);

    Optional<MediaSubscriptionResource> findBySubscriptionIdAndLink(int subscriptionId, String link);

    /** 派生删除 = 先 select 再逐个 em.remove,必须在事务里执行(无外围事务的调用方会抛 TransactionRequiredException)。 */
    @Transactional
    void deleteBySubscriptionId(int subscriptionId);
}
