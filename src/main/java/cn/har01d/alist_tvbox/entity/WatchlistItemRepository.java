package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Integer> {
    List<WatchlistItem> findByUidOrderByCreatedTimeDesc(int uid);

    List<WatchlistItem> findByUidAndStatusOrderByCreatedTimeDesc(int uid, String status);

    Optional<WatchlistItem> findByUidAndVodId(int uid, String vodId);

    boolean existsByUidAndVodId(int uid, String vodId);

    long countByUid(int uid);

    long deleteByUidAndVodId(int uid, String vodId);
}
