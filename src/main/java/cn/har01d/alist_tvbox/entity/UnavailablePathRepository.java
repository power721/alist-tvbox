package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UnavailablePathRepository extends JpaRepository<UnavailablePath, Integer> {
    void deleteByPath(String path);

    UnavailablePath findByPath(String path);

    boolean existsByPath(String path);
}
