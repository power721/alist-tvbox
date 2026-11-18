package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MediaProbeRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MediaProbeRepository extends JpaRepository<MediaProbeRun, String> {
    List<MediaProbeRun> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);
    List<MediaProbeRun> findByStatusIn(List<String> states);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MediaProbeRun r where r.id = :id")
    Optional<MediaProbeRun> findForUpdate(@Param("id") String id);
}
