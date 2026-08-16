package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LiveFollowRepository extends JpaRepository<LiveFollow, Integer> {
    List<LiveFollow> findByUidOrderByCreatedTimeDesc(int uid);

    Optional<LiveFollow> findByUidAndPlatformAndRoomId(int uid, String platform, String roomId);

    long deleteByUidAndPlatformAndRoomId(int uid, String platform, String roomId);

    long countByUid(int uid);
}
