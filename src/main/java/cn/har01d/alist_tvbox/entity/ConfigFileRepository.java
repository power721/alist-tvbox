package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigFileRepository extends JpaRepository<ConfigFile, Integer> {
    ConfigFile findByPath(String path);

    boolean existsByPath(String path);
}
