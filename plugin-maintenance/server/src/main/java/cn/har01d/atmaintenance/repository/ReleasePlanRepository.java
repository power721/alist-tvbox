package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ReleasePlan;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ReleasePlanRepository extends JpaRepository<ReleasePlan, String> {
    Optional<ReleasePlan> findBySourceIdAndRequestKey(String sourceId, String requestKey);
    boolean existsByActiveSourceId(String sourceId);
    List<ReleasePlan> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);

    @Query("select p.sourceId from ReleasePlan p where p.id = :id")
    Optional<String> findSourceId(@Param("id") String id);
}
