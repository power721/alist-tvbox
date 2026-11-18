package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.AtBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AtBindingRepository extends JpaRepository<AtBinding, String> {
    Optional<AtBinding> findByInstanceKeyAndPluginId(String instanceKey, int pluginId);
}
