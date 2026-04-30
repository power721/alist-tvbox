package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeiniuRepository extends JpaRepository<Feiniu, Integer> {
    Optional<Feiniu> findByName(String name);

    boolean existsByName(String name);
}
