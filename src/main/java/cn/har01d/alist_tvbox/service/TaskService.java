package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.domain.TaskType;
import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.TaskRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class TaskService {
    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
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
            taskRepository.delete(task);
        });
    }

    public Task addIndexTask(IndexRequest request) {
        Task task = new Task();
        task.setType(TaskType.INDEX);
        task.setName("索引站点 - " + request.getSite());
        return taskRepository.save(task);
    }

    public void startTask(Integer id) {
        Task task = getById(id);
        task.setStatus(TaskStatus.RUNNING);
        taskRepository.save(task);
    }

    public void updateTaskData(Integer id, String data) {
        Task task = getById(id);
        task.setData(data);
        taskRepository.save(task);
    }

    public void updateTaskSummary(Integer id, String summary) {
        Task task = getById(id);
        task.setSummary(summary);
        taskRepository.save(task);
    }

    public void completeTask(Integer id) {
        Task task = getById(id);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
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
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.FAILED);
        task.setEndTime(Instant.now());
        task.setError(error);
        taskRepository.save(task);
    }

    public Task cancelTask(Integer id) {
        Task task = getById(id);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return task;
        }
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.CANCELLED);
        task.setEndTime(Instant.now());
        return taskRepository.save(task);
    }
}
