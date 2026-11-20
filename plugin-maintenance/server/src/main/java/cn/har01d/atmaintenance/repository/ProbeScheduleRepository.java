package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ProbeSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProbeScheduleRepository extends JpaRepository<ProbeSchedule, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProbeSchedule s where s.sourceId = :sourceId")
    Optional<ProbeSchedule> findForUpdate(@Param("sourceId") String sourceId);

    List<ProbeSchedule> findTop20ByEnabledTrueAndSuspendedFalseAndNextDueAtLessThanEqualOrderByNextDueAtAscSourceIdAsc(Instant now);
    List<ProbeSchedule> findTop100ByActiveRunIdIsNotNullOrderBySourceIdAsc();
}
