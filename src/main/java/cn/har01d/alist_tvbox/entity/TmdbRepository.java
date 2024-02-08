package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TmdbRepository extends JpaRepository<Tmdb, Integer> {
    List<Tmdb> getByName(String name);

    Optional<Tmdb> findByTypeAndTmdbId(String type, Integer tmdbId);
}
