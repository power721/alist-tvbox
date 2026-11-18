package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.service.MaintenanceAutomationStore;
import cn.har01d.atmaintenance.service.AutomaticPublicationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance/automation")
@RequiredArgsConstructor
public class AutomationController {
    private final MaintenanceAutomationStore tasks;
    private final AutomaticPublicationService publications;

    @GetMapping("/tasks")
    public List<ObjectNode> tasks() { return tasks.list(); }

    @GetMapping("/publication-policy")
    public java.util.Map<String, Object> policy() {
        return java.util.Map.of("enabled", publications.enabled(), "defaultMode", "incremental", "persistentQueue", true);
    }
}
