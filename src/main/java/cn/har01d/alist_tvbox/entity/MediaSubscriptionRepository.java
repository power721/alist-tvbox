package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MediaSubscriptionRepository extends JpaRepository<MediaSubscription, Integer> {
    List<MediaSubscription> findByUidOrderByCreatedTimeDesc(int uid);

    Page<MediaSubscription> findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(String status, long time, Pageable pageable);
}
