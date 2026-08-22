package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MediaSubscriptionRepository extends JpaRepository<MediaSubscription, Integer> {
    List<MediaSubscription> findByUidOrderByCreatedTimeDesc(int uid);

    Page<MediaSubscription> findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(String status, long time, Pageable pageable);

    /** 仅更新封面快照列:预热回填与用户编辑并发时,不做全实体 merge 覆盖其他字段。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.coverUrl = :cover where s.id = :id")
    int updateCoverUrl(Integer id, String cover);
}
