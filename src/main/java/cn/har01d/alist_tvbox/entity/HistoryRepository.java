package cn.har01d.alist_tvbox.entity;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoryRepository extends JpaRepository<History, Integer> {
    History findByKey(String key);

    void deleteByKey(String key);

    Page<History> findByUid(int uid, Pageable pageable);

    List<History> findAllByUid(int uid, Sort sort);
}
