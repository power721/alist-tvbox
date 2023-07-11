package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaRepository extends JpaRepository<Meta, Integer> {
    Meta findByPath(String path);
    boolean existsByPath(String path);
    Page<Meta> findByPathStartsWith(String prefix, Pageable pageable);
    Page<Meta> findByPathStartsWithAndScoreGreaterThanEqual(String prefix, Integer score, Pageable pageable);
}
