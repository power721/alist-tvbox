package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaybackTokenRepository extends JpaRepository<PlaybackToken, Integer> {
    Optional<PlaybackToken> findByToken(String token);

    List<PlaybackToken> findByUid(int uid);

    Optional<PlaybackToken> findByUidAndSyncScope(int uid, String syncScope);

    List<PlaybackToken> findByUidAndSyncScopeIsNull(int uid);

    void deleteByIdAndUid(Integer id, int uid);
}
