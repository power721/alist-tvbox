package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MaintenanceArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<MaintenanceArtifact, String> {
    Optional<MaintenanceArtifact> findBySourceIdAndSha256(String sourceId, String sha256);
    List<MaintenanceArtifact> findBySourceIdOrderByCreatedAtDesc(String sourceId);
}
