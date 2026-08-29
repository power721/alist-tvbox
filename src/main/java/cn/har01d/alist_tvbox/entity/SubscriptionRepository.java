package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Integer> {
    Optional<Subscription> findBySid(String sid);

    Optional<Subscription> findByUrl(String url);

    /** 全局默认(0)+ 指定用户的个人订阅:普通用户的可见口径。 */
    java.util.List<Subscription> findByOwnerUidOrOwnerUidOrderByIdAsc(int global, int ownerUid);
}
