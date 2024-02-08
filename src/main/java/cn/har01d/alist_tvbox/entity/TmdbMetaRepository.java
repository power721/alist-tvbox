package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TmdbMetaRepository extends JpaRepository<TmdbMeta, Integer> {
    TmdbMeta findByPath(String path);

    Page<TmdbMeta> findByPathContains(String text, Pageable pageable);
}
