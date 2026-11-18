package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.service.WorkbenchService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class WorkbenchController {
    private final WorkbenchService service;

    @GetMapping("/sources/overview")
    public JsonNode overview() { return service.overview(); }

    @GetMapping("/sources/{id}/maintenance-log")
    public ResponseEntity<JsonNode> log(@PathVariable String id, @RequestParam(required = false) String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.log(id, runId));
    }

    @PostMapping("/maintenance-log-imports")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode importLog(@RequestBody JsonNode input) throws IOException { return service.importLog(input); }

    @GetMapping("/maintenance-log-imports/{id}")
    public ResponseEntity<JsonNode> imported(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.importedLog(id));
    }
}
