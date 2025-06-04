package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.domain.TaskType;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.TaskRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
public class TaskService {
    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @PostConstruct
    public void init() {
        cancelAll();
    }

    public Page<Task> list(Pageable pageable) {
        return taskRepository.findAll(pageable);
    }

    public Task getById(Integer id) {
        return taskRepository.findById(id).orElseThrow(() -> new NotFoundException("任务不存在"));
    }

    public void delete(Integer id) {
        taskRepository.findById(id).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.RUNNING) {
                throw new BadRequestException("任务在运行中");
            }
            log.info("delete task {}: {}", id, task.getName());
            taskRepository.delete(task);
        });
    }

    public Task addIndexTask(Site site, String indexName) {
        Task task = new Task();
        task.setType(TaskType.INDEX);
        task.setName("索引站点 - " + site.getName() + " - " + indexName);
        task.setCreatedTime(Instant.now());
        return taskRepository.save(task);
    }

    public Task addScrapeTask(Site site) {
        Task task = new Task();
        task.setType(TaskType.SCRAPE);
        task.setName("刮削索引文件 - " + site.getName());
        task.setCreatedTime(Instant.now());
        return taskRepository.save(task);
    }

    public Task addSyncMeta() {
        Task task = new Task();
        task.setType(TaskType.SYNC_META);
        task.setName("同步电影数据");
        task.setCreatedTime(Instant.now());
        return taskRepository.save(task);
    }

    public void startTask(Integer id) {
        Task task = getById(id);
        log.info("start task {}: {}", id, task.getName());
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(Instant.now());
        taskRepository.save(task);
    }

    public void updateTaskData(Integer id, String data) {
        Task task = getById(id);
        task.setData(data);
        task.setUpdatedTime(Instant.now());
        taskRepository.save(task);
    }

    public Task updateTaskSummary(Integer id, String summary) {
        Task task = getById(id);
        task.setSummary(summary);
        task.setUpdatedTime(Instant.now());
        return taskRepository.save(task);
    }

    public void completeTask(Integer id) {
        Task task = getById(id);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
        log.info("complete task {}: {}", id, task.getName());
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.OK);
        task.setEndTime(Instant.now());
        taskRepository.save(task);
    }

    public void failTask(Integer id, String error) {
        Task task = getById(id);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
        log.info("fail task {}: {}", id, task.getName());
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.FAILED);
        task.setEndTime(Instant.now());
        task.setError(error);
        taskRepository.save(task);
    }

    public void cancelAll() {
        cancelAllByStatus(TaskStatus.READY);
        cancelAllByStatus(TaskStatus.RUNNING);
    }

    public void cancelAllByStatus(TaskStatus status) {
        Task t = new Task();
        t.setStatus(status);
        Example<Task> example = Example.of(t);
        List<Task> tasks = taskRepository.findAll(example);
        for (Task task : tasks) {
            log.info("cancel task {}: {}", task.getId(), task.getName());
            task.setStatus(TaskStatus.COMPLETED);
            task.setResult(TaskResult.CANCELLED);
            task.setEndTime(Instant.now());
            taskRepository.save(task);
        }
    }

    public Task cancelTask(Integer id) {
        Task task = getById(id);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return task;
        }
        log.info("cancel task {}: {}", id, task.getName());
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.CANCELLED);
        task.setEndTime(Instant.now());
        return taskRepository.save(task);
    }

    public void clean(int days) {
        if (days == 0) {
            return;
        }

        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant time = today.minus(days, ChronoUnit.DAYS);
        long count = taskRepository.deleteAllByCreatedTimeBefore(time);
        log.info("deleted {} tasks which create before {} days ago", count, days);
    }
}
