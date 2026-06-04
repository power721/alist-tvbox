package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginRepository extends JpaRepository<Plugin, Integer> {
    Optional<Plugin> findByUrl(String url);

    Optional<Plugin> findByExternalId(String externalId);

    List<Plugin> findAllByOrderBySortOrderAscIdAsc();

    List<Plugin> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
