package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MaintenanceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<MaintenanceProfile, String> {
    Optional<MaintenanceProfile> findBySourceIdAndSha256(String sourceId, String sha256);
    List<MaintenanceProfile> findBySourceIdOrderByCreatedAtDesc(String sourceId);
}
