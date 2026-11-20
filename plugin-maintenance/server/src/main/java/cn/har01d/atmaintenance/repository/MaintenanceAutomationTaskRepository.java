package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MaintenanceAutomationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MaintenanceAutomationTaskRepository extends JpaRepository<MaintenanceAutomationTask, String> {
    List<MaintenanceAutomationTask> findByKindAndStateInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
            String kind, Collection<String> states, Instant now);
    List<MaintenanceAutomationTask> findTop100ByOrderByCreatedAtDesc();
}
