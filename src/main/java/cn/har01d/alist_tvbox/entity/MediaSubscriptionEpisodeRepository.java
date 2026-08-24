package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionEpisodeRepository extends JpaRepository<MediaSubscriptionEpisode, Integer> {
    Optional<MediaSubscriptionEpisode> findBySubscriptionIdAndSeasonAndNumber(int subscriptionId, int season, int number);

    List<MediaSubscriptionEpisode> findBySubscriptionIdOrderByNumber(int subscriptionId);

    /** 派生删除 = 先 select 再逐个 em.remove,必须在事务里执行(无外围事务的调用方会抛 TransactionRequiredException)。 */
    @Transactional
    void deleteBySubscriptionId(int subscriptionId);
}
