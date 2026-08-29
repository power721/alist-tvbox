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

    boolean existsByMountPath(String mountPath);

    /** 共享挂载守卫:该 share 是否仍被其它订阅的主源引用。 */
    boolean existsByShareIdAndIdNot(Integer shareId, Integer id);

    Page<MediaSubscription> findByStatusAndNextCheckTimeLessThanEqualOrderByNextCheckTimeAsc(String status, long time, Pageable pageable);

    /** 仅更新封面快照列:预热回填与用户编辑并发时,不做全实体 merge 覆盖其他字段。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.coverUrl = :cover where s.id = :id")
    int updateCoverUrl(Integer id, String cover);

    /** 追平标记只升不降(资源侧集数回落不回退标记):定向更新,避免读路径全实体 save 与巡检任务互相覆盖。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.caughtUpEpisode = :episode where s.id = :id"
            + " and (s.caughtUpEpisode is null or s.caughtUpEpisode < :episode)")
    int markCaughtUp(Integer id, int episode);
}
