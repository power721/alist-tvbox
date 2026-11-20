package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.PluginInput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginInputRepository extends JpaRepository<PluginInput, String> {
    Optional<PluginInput> findByRequestKey(String requestKey);
    List<PluginInput> findTop20BySourceIdOrderByCreatedAtDesc(String sourceId);
}
