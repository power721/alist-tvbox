package cn.har01d.alist_tvbox.entity;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MetaRepository extends JpaRepository<Meta, Integer> {
    @Modifying
    @Transactional
    @Query("UPDATE Meta m SET m.disabled = true WHERE m.path LIKE ?1%")
    int disableByPathStartsWith(String pathPrefix);

    @Modifying
    @Transactional
    @Query("UPDATE Meta m SET m.disabled = false WHERE m.path LIKE ?1%")
    int enableByPathStartsWith(String pathPrefix);

    @Modifying
    @Transactional
    @Query("UPDATE Meta m SET m.disabled = true WHERE m.tid = ?1")
    int disableByTid(int tid);

    @Modifying
    @Transactional
    @Query("UPDATE Meta m SET m.disabled = false WHERE m.tid = ?1")
    int enableByTid(int tid);

    Meta findByPath(String path);

    List<Meta> findByTmdb(Tmdb tmdb);

    List<Meta> findByMovieNull();

    List<Meta> findByPathContains(String text);

    Page<Meta> findByPathContains(String text, Pageable pageable);

    boolean existsByPath(String path);

    boolean existsByPathStartsWith(String path);

    Page<Meta> findByPathStartsWith(String prefix, Pageable pageable);

    Page<Meta> findByPathStartsWithAndYear(String prefix, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndYearLessThan(String prefix, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreGreaterThanEqual(String prefix, Integer score, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreGreaterThanEqualAndYear(String prefix, Integer score, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreGreaterThanEqualAndYearLessThan(String prefix, Integer score, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreLessThan(String prefix, Integer score, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreLessThanAndYear(String prefix, Integer score, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreLessThanAndYearLessThan(String prefix, Integer score, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreIsNull(String prefix, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreIsNullAndYear(String prefix, Integer year, Pageable pageable);

    Page<Meta> findByPathStartsWithAndScoreIsNullAndYearLessThan(String prefix, Integer year, Pageable pageable);
}
