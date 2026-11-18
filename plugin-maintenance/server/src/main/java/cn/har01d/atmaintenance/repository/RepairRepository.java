package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.RepairSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface RepairRepository extends JpaRepository<RepairSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RepairSession r where r.id = :id")
    Optional<RepairSession> findForUpdate(@Param("id") String id);

    List<RepairSession> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);
    List<RepairSession> findByStatusIn(Collection<String> statuses);
    List<RepairSession> findByAutomaticStatus(String status);
    Optional<RepairSession> findBySourceIdAndFaultSha256(String sourceId, String faultSha256);
}
