package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MediaSubscriptionEventRepository extends JpaRepository<MediaSubscriptionEvent, Integer> {
    List<MediaSubscriptionEvent> findTop100BySubscriptionIdOrderByCreatedTimeDesc(int subscriptionId);

    void deleteBySubscriptionId(int subscriptionId);
}
