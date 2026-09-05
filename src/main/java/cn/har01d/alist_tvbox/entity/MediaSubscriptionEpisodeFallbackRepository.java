package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionEpisodeFallbackRepository extends JpaRepository<MediaSubscriptionEpisodeFallback, Integer> {

    /** 某订阅全部补集行(各状态,调用方按 expires_at 自行过滤)。 */
    List<MediaSubscriptionEpisodeFallback> findBySubscriptionId(int subscriptionId);

    /** 某订阅某集的补集行((subscription, episode) 唯一,upsert 先查后写)。 */
    Optional<MediaSubscriptionEpisodeFallback> findBySubscriptionIdAndEpisode(int subscriptionId, int episode);

    /** 同集覆盖旧行 = update 全字段;派生写方法需事务(后台线程无外围事务)。 */
    @Transactional
    void deleteBySubscriptionId(int subscriptionId);
}
