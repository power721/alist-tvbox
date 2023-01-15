package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface TaskRepository extends JpaRepository<Task, Integer> {
    long deleteAllByCreatedTimeBefore(Instant time);
}
