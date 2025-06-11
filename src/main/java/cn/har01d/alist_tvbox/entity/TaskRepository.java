package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.domain.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Integer> {
    long deleteAllByCreatedTimeBefore(Instant time);

    long countByStatusAndType(TaskStatus status, TaskType type);
}