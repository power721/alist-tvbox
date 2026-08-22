package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionEpisodeRepository extends JpaRepository<MediaSubscriptionEpisode, Integer> {
    Optional<MediaSubscriptionEpisode> findBySubscriptionIdAndSeasonAndNumber(int subscriptionId, int season, int number);

    List<MediaSubscriptionEpisode> findBySubscriptionIdOrderByNumber(int subscriptionId);

    void deleteBySubscriptionId(int subscriptionId);
}
