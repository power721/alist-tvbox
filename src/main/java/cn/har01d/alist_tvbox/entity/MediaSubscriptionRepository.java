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

    /** 仅更新封面/跨源身份快照:预热回填与用户编辑并发时,不做全实体 merge 覆盖其他字段。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.doubanId = :doubanId, s.coverUrl = :cover,"
            + " s.coverFallbackUrl = :fallbackCover, s.coverFallbackStatus = :fallbackStatus"
            + " where s.id = :id and s.metaProvider = :provider and s.metaId = :metaId"
            + " and ((:season is null and s.season is null) or s.season = :season)"
            + " and ((:expectedDoubanId is null and s.doubanId is null) or s.doubanId = :expectedDoubanId)")
    int updateCoverSnapshot(Integer id, String provider, String metaId, Integer season,
                            Integer expectedDoubanId, Integer doubanId, String cover,
                            String fallbackCover, String fallbackStatus);

    /** 外部元数据结果仅回写快照列,并以请求发起时的条目身份作乐观门禁。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.coverUrl = :cover, s.metaSyncTime = :syncTime,"
            + " s.officialEpisodes = :officialEpisodes, s.officialTotal = :officialTotal,"
            + " s.officialStatus = :officialStatus, s.nextAirTime = :nextAirTime,"
            + " s.aliases = :aliases, s.schedule = :schedule"
            + " where s.id = :id and s.metaProvider = :provider and s.metaId = :metaId"
            + " and ((:season is null and s.season is null) or s.season = :season)"
            + " and ((:expectedDoubanId is null and s.doubanId is null) or s.doubanId = :expectedDoubanId)")
    int updateMetadataSnapshot(Integer id, String provider, String metaId, Integer season,
                               Integer expectedDoubanId, String cover, Long syncTime, Integer officialEpisodes,
                               Integer officialTotal, String officialStatus, Long nextAirTime,
                               String aliases, String schedule);

    /** 巡检只写运行态；身份变化时旧巡检结果直接丢弃。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.shareId = :shareId, s.status = :status,"
            + " s.currentEpisodes = :currentEpisodes, s.maxEpisode = :maxEpisode,"
            + " s.stallCount = :stallCount, s.nextCheckTime = :nextCheckTime,"
            + " s.lastCheckTime = :lastCheckTime, s.updatedTime = :updatedTime"
            + " where s.id = :id"
            + " and ((:provider is null and s.metaProvider is null) or s.metaProvider = :provider)"
            + " and ((:metaId is null and s.metaId is null) or s.metaId = :metaId)"
            + " and ((:season is null and s.season is null) or s.season = :season)"
            + " and ((:expectedDoubanId is null and s.doubanId is null) or s.doubanId = :expectedDoubanId)")
    int updateCheckState(Integer id, String provider, String metaId, Integer season, Integer expectedDoubanId,
                         Integer shareId, String status, Integer currentEpisodes, Integer maxEpisode,
                         int stallCount, Long nextCheckTime, Long lastCheckTime, Long updatedTime);

    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.currentEpisodes = :currentEpisodes,"
            + " s.maxEpisode = :maxEpisode, s.updatedTime = :updatedTime where s.id = :id")
    int updateEpisodeCounters(Integer id, Integer currentEpisodes, Integer maxEpisode, Long updatedTime);

    /** 追平标记只升不降(资源侧集数回落不回退标记):定向更新,避免读路径全实体 save 与巡检任务互相覆盖。 */
    @Transactional
    @Modifying
    @Query("update MediaSubscription s set s.caughtUpEpisode = :episode where s.id = :id"
            + " and (s.caughtUpEpisode is null or s.caughtUpEpisode < :episode)")
    int markCaughtUp(Integer id, int episode);
}
