package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.dto.SourceKeyUsageCount;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HistoryRepository extends JpaRepository<History, Integer> {
    List<History> findAllByUidAndSourceKindIsNotNull(int uid, Sort sort);

    Page<History> findPageByUidAndSourceKindIsNotNull(int uid, Pageable pageable);

    /** 「网页播放」筛选:仅返回 sourceKey 命中可播放白名单的记录。 */
    Page<History> findPageByUidAndSourceKeyIn(int uid, Collection<String> sourceKeys, Pageable pageable);

    /** 管理页「按安全 Token 筛选」:uid 级分区(全局互通)。 */
    Page<History> findPageByUidAndSourceKindIsNotNullAndSyncScopeIsNull(int uid, Pageable pageable);

    /** 管理页「按安全 Token 筛选」:指定同步分区(安全 Token 或 token/订阅id)。 */
    Page<History> findPageByUidAndSourceKindIsNotNullAndSyncScope(int uid, String syncScope, Pageable pageable);

    /** 该用户现存记录出现过的同步分区(distinct,含 null 行=uid 级),供筛选选项。 */
    @Query("SELECT DISTINCT h.syncScope FROM History h WHERE h.uid = :uid AND h.sourceKind IS NOT NULL")
    List<String> findDistinctSyncScope(@Param("uid") int uid);

    List<History> findAllByUidAndSourceKindAndSourceKeyAndVodId(
            int uid, String sourceKind, String sourceKey, String vodId);

    List<History> findByUidAndSourceKindAndSourceKey(int uid, String sourceKind, String sourceKey);

    /** 追更观看进度:同一订阅在各端的播放记录(vodId = msub:{订阅id}),多端由播放记录同步天然合并。 */
    List<History> findByUidAndVodId(int uid, String vodId);

    List<History> findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(int uid, long changeSeq, Sort sort);

    List<History> findByUidAndSourceKindAndChangeSeqGreaterThan(int uid, String sourceKind, long changeSeq, Sort sort);

    List<History> findByUidAndSourceKindInAndChangeSeqGreaterThan(int uid, List<String> sourceKinds, long changeSeq, Sort sort);

    // ── 同步分区查询:syncScope 为空 = uid 级(返回全部);非空 = 仅该分区 ──────────

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind = :sourceKind AND h.sourceKey = :sourceKey AND h.vodId = :vodId")
    List<History> findSyncByIdentity(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                     @Param("sourceKind") String sourceKind, @Param("sourceKey") String sourceKey,
                                     @Param("vodId") String vodId);

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind IS NOT NULL AND h.changeSeq > :since")
    List<History> findSyncByCursor(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                   @Param("since") long since, Sort sort);

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind = :sourceKind AND h.changeSeq > :since")
    List<History> findSyncByCursorAndKind(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                          @Param("sourceKind") String sourceKind, @Param("since") long since, Sort sort);

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind IN :sourceKinds AND h.changeSeq > :since")
    List<History> findSyncByCursorAndKinds(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                           @Param("sourceKinds") List<String> sourceKinds, @Param("since") long since, Sort sort);

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind IS NOT NULL")
    List<History> findSyncAll(@Param("uid") int uid, @Param("syncScope") String syncScope, Sort sort);

    @Query("SELECT h FROM History h WHERE h.uid = :uid "
            + "AND (:syncScope IS NULL OR h.syncScope = :syncScope) "
            + "AND h.sourceKind = :sourceKind AND h.sourceKey = :sourceKey")
    List<History> findSyncBySite(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                 @Param("sourceKind") String sourceKind, @Param("sourceKey") String sourceKey);

    /** 各 sourceKey 的使用次数(按次数倒序),插件预热清单据此把常用插件排前。 */
    @Query("SELECT h.sourceKey AS sourceKey, COUNT(h) AS total FROM History h "
            + "WHERE h.sourceKind = :sourceKind GROUP BY h.sourceKey ORDER BY total DESC")
    List<SourceKeyUsageCount> countBySourceKey(@Param("sourceKind") String sourceKind);
}
