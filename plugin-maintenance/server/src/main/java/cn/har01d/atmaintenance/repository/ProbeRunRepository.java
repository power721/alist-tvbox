package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ProbeRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProbeRunRepository extends JpaRepository<ProbeRun, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProbeRun r where r.id = :id")
    Optional<ProbeRun> findForUpdate(@Param("id") String id);

    List<ProbeRun> findTop30BySourceIdOrderByCreatedAtDesc(String sourceId);

    @Query("select r from ProbeRun r where r.executionMode = 'managed_source' and not exists "
            + "(select n.id from ProbeRun n where n.sourceId = r.sourceId and n.executionMode = 'managed_source' "
            + "and (n.createdAt > r.createdAt or (n.createdAt = r.createdAt and n.id > r.id)))")
    List<ProbeRun> latestLiveRuns();
    List<ProbeRun> findByStatusIn(List<String> statuses);
    Optional<ProbeRun> findFirstBySourceIdAndArtifactSha256OrderByCreatedAtDesc(String sourceId, String sha256);
    Optional<ProbeRun> findBySourceIdAndMaintenanceKey(String sourceId, String key);
    List<ProbeRun> findBySourceIdAndMaintenanceStatusIn(String sourceId, List<String> statuses);
    List<ProbeRun> findByMaintenanceStatusIn(List<String> statuses);
    List<ProbeRun> findTop30BySourceIdAndMaintenanceKeyIsNotNullOrderByCreatedAtDesc(String sourceId);

    @Query("select r from ProbeRun r where r.sourceId = :sourceId and r.triggerKind = 'scheduled' "
            + "and r.createdAt >= :start and r.createdAt <= :end order by r.createdAt desc, r.id desc")
    List<ProbeRun> scheduledWindow(@Param("sourceId") String sourceId, @Param("start") Instant start,
                                  @Param("end") Instant end, Pageable pageable);
}
