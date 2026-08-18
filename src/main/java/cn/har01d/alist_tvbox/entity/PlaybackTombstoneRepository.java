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

    // ── 同步分区查询 ─────────────────────────────────────────────────────────
    // syncScope 为空 = uid 级(走上面的派生查询,跨所有分区);
    // 非空 = 该分区 ∪ uid 全局(sync_scope IS NULL)墓碑——管理端/网页删除落在 uid 全局分区,
    // 必须下达给 scoped 客户端,否则删除对其不可见、记录被下次 PUSH 复活。

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) "
            + "AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey AND t.vodId = :vodId")
    List<PlaybackTombstone> findSyncByIdentity(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                               @Param("sourceKind") String sourceKind, @Param("sourceKey") String sourceKey,
                                               @Param("vodId") String vodId);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) "
            + "AND t.scope = :scope AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey")
    List<PlaybackTombstone> findSyncSite(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                         @Param("scope") String scope, @Param("sourceKind") String sourceKind,
                                         @Param("sourceKey") String sourceKey);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) AND t.scope = :scope")
    List<PlaybackTombstone> findSyncAllScope(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                             @Param("scope") String scope);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursor(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                             @Param("since") long since, Sort sort);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) "
            + "AND t.sourceKind = :sourceKind AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursorAndKind(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                                    @Param("sourceKind") String sourceKind, @Param("since") long since, Sort sort);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) "
            + "AND t.sourceKind IN :sourceKinds AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncByCursorAndKinds(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                                     @Param("sourceKinds") List<String> sourceKinds, @Param("since") long since, Sort sort);

    // all 作用域(清空全部)墓碑不带 sourceKind 但对所有来源生效,分源拉取需单独取回
    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND (t.syncScope = :syncScope OR t.syncScope IS NULL) "
            + "AND t.sourceKind IS NULL AND t.changeSeq > :since")
    List<PlaybackTombstone> findSyncAllBreadth(@Param("uid") int uid, @Param("syncScope") String syncScope,
                                               @Param("since") long since, Sort sort);

    // ── 管理端墓碑清理:跨分区(忽略 sync_scope)按身份匹配,供误报墓碑的手动清除 ────

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid "
            + "AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey AND t.vodId = :vodId")
    List<PlaybackTombstone> findItemAnyScope(@Param("uid") int uid, @Param("sourceKind") String sourceKind,
                                             @Param("sourceKey") String sourceKey, @Param("vodId") String vodId);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid AND t.scope = 'site' "
            + "AND t.sourceKind = :sourceKind AND t.sourceKey = :sourceKey")
    List<PlaybackTombstone> findSiteAnyScope(@Param("uid") int uid, @Param("sourceKind") String sourceKind,
                                             @Param("sourceKey") String sourceKey);

    @Query("SELECT t FROM PlaybackTombstone t WHERE t.uid = :uid AND t.scope = 'all'")
    List<PlaybackTombstone> findAllAnyScope(@Param("uid") int uid);
}
