package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NavigationRepository extends JpaRepository<Navigation, Integer> {
    int countByParentId(Integer id);

    boolean existsByValue(String value);

    Optional<Navigation> findFirstByValue(String value);

    Optional<Navigation> findFirstByValueAndType(String value, int type);
}