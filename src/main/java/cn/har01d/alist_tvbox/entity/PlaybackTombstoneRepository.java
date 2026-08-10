package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlaybackTombstoneRepository extends JpaRepository<PlaybackTombstone, Integer> {
    List<PlaybackTombstone> findAllByUidAndSourceKindAndSourceKeyAndVodId(
            int uid, String sourceKind, String sourceKey, String vodId);

    PlaybackTombstone findFirstByUidAndScopeOrderByDeletedAtDesc(int uid, String scope);

    PlaybackTombstone findFirstByUidAndScopeAndSourceKindAndSourceKeyOrderByDeletedAtDesc(int uid, String scope, String sourceKind, String sourceKey);

    List<PlaybackTombstone> findByUidAndChangeSeqGreaterThan(int uid, long changeSeq, Sort sort);

    List<PlaybackTombstone> findByUidAndSourceKindAndChangeSeqGreaterThan(int uid, String sourceKind, long changeSeq, Sort sort);

    List<PlaybackTombstone> findByUidAndSourceKindInAndChangeSeqGreaterThan(int uid, List<String> sourceKinds, long changeSeq, Sort sort);

    // all 作用域的墓碑不带 sourceKind,分源拉取时需单独取回,否则"清空全部"同步不到分源客户端
    List<PlaybackTombstone> findByUidAndSourceKindIsNullAndChangeSeqGreaterThan(int uid, long changeSeq, Sort sort);

    void deleteByExpireAtBefore(long expireAt);

    // ── 同步分区查询:syncScope 为空 = uid 级;非空 = 仅该分区 ──────────────────

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) "
            + "AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey AND t.vodId = :vodId")
    List<PlaybackTombstone> findSyncByIdentity(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                               @Param("sourceKind") String sourceKind, @Param("sourceKey") String sourceKey,
                                               @Param("vodId") String vodId);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) "
            + "AND t.scope = :scope AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey")
    List<PlaybackTombstone> findSyncSite(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                         @Param("scope") String scope, @Param("sourceKind") String sourceKind,
                                         @Param("sourceKey") String sourceKey);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) AND t.scope = :scope")
    List<PlaybackTombstone> findSyncAllScope(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                             @Param("scope") String scope);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursor(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                             @Param("since") long since, Sort sort);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) "
            + "AND t.sourceKind = :sourceKind AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursorAndKind(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                                    @Param("sourceKind") String sourceKind, @Param("since") long since, Sort sort);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) "
            + "AND t.sourceKind IN :sourceKinds AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursorAndKinds(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                                     @Param("sourceKinds") List<String> sourceKinds, @Param("since") long since, Sort sort);

    // all 作用域(清空全部)墓碑不带 sourceKind 但对所有来源生效,分源拉取需单独取回
    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (:syncScope IS NULL OR t.syncScope = :syncScope) "
            + "AND t.sourceKind IS NULL AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncAllBreadth(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                               @Param("since") long since, Sort sort);
}
