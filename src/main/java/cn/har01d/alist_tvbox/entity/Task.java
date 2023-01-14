package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.domain.TaskType;
import lombok.*;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.time.Instant;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;
    private TaskType type;
    private TaskStatus status = TaskStatus.READY;
    private TaskResult result;
    private String data;
    private String summary;
    private String error;
    private final Instant startTime = Instant.now();
    private Instant endTime;
}
