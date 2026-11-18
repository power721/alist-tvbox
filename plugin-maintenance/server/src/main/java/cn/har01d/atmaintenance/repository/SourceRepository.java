package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MaintenanceSource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface SourceRepository extends JpaRepository<MaintenanceSource, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MaintenanceSource s where s.id = :id")
    Optional<MaintenanceSource> findForUpdate(@Param("id") String id);
}
