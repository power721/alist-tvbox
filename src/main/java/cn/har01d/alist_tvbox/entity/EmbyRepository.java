package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmbyRepository extends JpaRepository<Emby, Integer> {
    Optional<Emby> findByName(String name);

    Optional<Emby> findByUrl(String url);

    boolean existsByName(String name);

    boolean existsByUrl(String url);
}
