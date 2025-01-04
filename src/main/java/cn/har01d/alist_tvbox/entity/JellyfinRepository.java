package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JellyfinRepository extends JpaRepository<Jellyfin, Integer> {
    Optional<Jellyfin> findByName(String name);

    Optional<Jellyfin> findByUrl(String url);

    boolean existsByName(String name);

    boolean existsByUrl(String url);
}
