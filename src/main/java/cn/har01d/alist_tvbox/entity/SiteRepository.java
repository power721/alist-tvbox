package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Integer> {
    List<Site> findAllByDisabledFalse(Sort sort);

    Optional<Site> findByName(String name);

    Optional<Site> findByUrl(String url);

    boolean existsByName(String name);

    boolean existsByUrl(String url);
}
