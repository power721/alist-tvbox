package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
