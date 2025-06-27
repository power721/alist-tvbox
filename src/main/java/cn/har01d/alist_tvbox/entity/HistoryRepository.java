package cn.har01d.alist_tvbox.entity;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoryRepository extends JpaRepository<History, Integer> {
    History findByCidAndKey(int cid, String key);

    void deleteByCidAndKey(int cid, String key);

    List<History> findByCid(int cid);

    void deleteByCid(int cid);
}
