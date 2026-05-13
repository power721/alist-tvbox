package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginFilterRepository extends JpaRepository<PluginFilter, Integer> {
    Optional<PluginFilter> findByUrl(String url);

    List<PluginFilter> findAllByOrderBySortOrderAscIdAsc();

    List<PluginFilter> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
