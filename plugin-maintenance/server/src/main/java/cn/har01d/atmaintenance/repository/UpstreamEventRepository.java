package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.UpstreamEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UpstreamEventRepository extends JpaRepository<UpstreamEvent, String> {
    Optional<UpstreamEvent> findBySourceIdAndRequestKey(String sourceId, String requestKey);
    List<UpstreamEvent> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);
}
