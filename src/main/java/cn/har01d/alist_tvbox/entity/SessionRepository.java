package cn.har01d.alist_tvbox.entity;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, Integer> {
    Session findFirstByUsername(String username);

    long countByUsername(String username);

    List<Session> findAllByUsername(String username);

    Optional<Session> findByToken(String token);
}
