package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetaRepository extends JpaRepository<Meta, Integer> {
    Meta findByPath(String path);

    List<Meta> findByPathContains(String text);

    boolean existsByPath(String path);
    boolean existsByPathStartsWith(String path);

    Page<Meta> findByPathStartsWith(String prefix, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreGreaterThanEqual(String prefix, Integer score, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreLessThan(String prefix, Integer score, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreIsNull(String prefix, Pageable pageable);
}
