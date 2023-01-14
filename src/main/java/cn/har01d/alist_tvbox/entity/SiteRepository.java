package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Integer> {
    Optional<Site> findByName(String name);

    Optional<Site> findByUrl(String url);

    boolean existsByName(String name);

    boolean existsByUrl(String url);
}
