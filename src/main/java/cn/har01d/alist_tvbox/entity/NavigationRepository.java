package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NavigationRepository extends JpaRepository<Navigation, Integer> {
    int countByParentId(Integer id);

    boolean existsByValue(String value);
}