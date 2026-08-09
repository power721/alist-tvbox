package cn.har01d.alist_tvbox.entity;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoryRepository extends JpaRepository<History, Integer> {
    List<History> findAllByUidAndSourceKindIsNullAndKey(int uid, String key);

    void deleteByUidAndSourceKindIsNullAndKey(int uid, String key);

    Page<History> findByUidAndSourceKindIsNull(int uid, Pageable pageable);

    List<History> findAllByUidAndSourceKindIsNull(int uid, Sort sort);

    List<History> findAllByUidAndSourceKindIsNotNull(int uid, Sort sort);

    Page<History> findPageByUidAndSourceKindIsNotNull(int uid, Pageable pageable);

    List<History> findAllByUidAndSourceKindAndSourceKeyAndVodId(
            int uid, String sourceKind, String sourceKey, String vodId);

    List<History> findByUidAndSourceKindAndSourceKey(int uid, String sourceKind, String sourceKey);

    List<History> findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(int uid, long changeSeq, Sort sort);

    List<History> findByUidAndSourceKindAndChangeSeqGreaterThan(int uid, String sourceKind, long changeSeq, Sort sort);

    List<History> findByUidAndSourceKindInAndChangeSeqGreaterThan(int uid, List<String> sourceKinds, long changeSeq, Sort sort);
}
