package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ModelCall;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;

public interface ModelCallRepository extends JpaRepository<ModelCall, String> {
    Optional<ModelCall> findByRepairIdAndRequestKey(String repairId, String requestKey);
    List<ModelCall> findByRepairIdOrderByAttemptNumberAsc(String repairId);
    List<ModelCall> findByStatusIn(Collection<String> states);
    List<ModelCall> findBySubmissionStatus(String state);
    List<ModelCall> findByModelNameAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String modelName, Instant start, Instant end);
}
