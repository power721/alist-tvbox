package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.RepairCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface RepairCandidateRepository extends JpaRepository<RepairCandidate, String> {
    List<RepairCandidate> findByRepairIdOrderByRoundNumberAsc(String repairId);
    Optional<RepairCandidate> findByModelCallId(String modelCallId);

    @Query("select c from RepairCandidate c, RepairSession r where c.repairId = r.id "
            + "and r.sourceId = :sourceId and c.sha256 <> r.baselineArtifactSha256 order by c.createdAt, c.id")
    List<RepairCandidate> findChangedCandidates(@Param("sourceId") String sourceId);
}
