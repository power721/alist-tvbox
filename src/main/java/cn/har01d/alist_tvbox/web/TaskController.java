package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.service.TaskService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public Page<Task> list(Pageable pageable) {
        return taskService.list(pageable);
    }

    @DeleteMapping
    public void clean(@RequestParam(required = false, defaultValue = "30") int days) {
        taskService.clean(days);
    }

    @GetMapping("/{id}")
    public Task getById(@PathVariable Integer id) {
        return taskService.getById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        taskService.delete(id);
    }

    @PostMapping("/{id}/cancel")
    public Task cancelTask(@PathVariable Integer id) {
        return taskService.cancelTask(id);
    }
}
