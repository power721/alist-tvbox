package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.SourceArchiveImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceImportRepository extends JpaRepository<SourceArchiveImport, String> {
    Optional<SourceArchiveImport> findByRequestKey(String requestKey);
    List<SourceArchiveImport> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);
}
