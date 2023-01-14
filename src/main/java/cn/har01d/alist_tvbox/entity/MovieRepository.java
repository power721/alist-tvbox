package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Integer> {
    Optional<Movie> findByPath(String path);
    Optional<Movie> findBySiteAndPath(Site site, String path);
    Optional<Movie> findBySiteIdAndPath(Integer siteId, String path);
    boolean existsBySiteIdAndPath(Integer siteId, String path);
}
