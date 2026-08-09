package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlaybackChangeSequenceRepository extends JpaRepository<PlaybackChangeSequence, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PlaybackChangeSequence s where s.id = :id")
    Optional<PlaybackChangeSequence> findByIdForUpdate(@Param("id") Integer id);
}
