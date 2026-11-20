package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.EmbeddedDeployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmbeddedDeploymentRepository extends JpaRepository<EmbeddedDeployment, String> {
    Optional<EmbeddedDeployment> findByPluginIdAndRequestKey(int pluginId, String requestKey);
    boolean existsByActivePluginId(int pluginId);
    boolean existsBySiteKeyAndStateIn(String siteKey, Collection<String> states);
    List<EmbeddedDeployment> findTop20ByPluginIdOrderByCreatedAtDesc(int pluginId);
}
