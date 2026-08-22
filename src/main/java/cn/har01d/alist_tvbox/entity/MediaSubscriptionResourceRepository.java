package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionResourceRepository extends JpaRepository<MediaSubscriptionResource, Integer> {
    List<MediaSubscriptionResource> findBySubscriptionIdOrderByScoreDesc(int subscriptionId);

    Optional<MediaSubscriptionResource> findBySubscriptionIdAndLink(int subscriptionId, String link);

    void deleteBySubscriptionId(int subscriptionId);
}
