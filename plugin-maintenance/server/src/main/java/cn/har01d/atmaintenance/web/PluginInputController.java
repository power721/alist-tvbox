package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.PluginInputService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class PluginInputController {
    private final PluginInputService inputs;

    @PostMapping("/at/plugin-inputs/preview")
    public PluginInputSummary previewAt(@Valid @RequestBody PreviewAtPluginInputRequest input) {
        return inputs.previewAt(input);
    }

    @PostMapping("/at/plugin-inputs")
    @ResponseStatus(HttpStatus.CREATED)
    public PluginInputSummary confirmAt(@Valid @RequestBody ConfirmAtPluginInputRequest input) {
        return inputs.confirmAt(input);
    }

    @PostMapping("/plugin-inputs/preview")
    public PluginInputSummary preview(@Valid @RequestBody PreviewPluginInputRequest input) {
        return inputs.preview(input);
    }

    @PostMapping("/plugin-inputs")
    @ResponseStatus(HttpStatus.CREATED)
    public PluginInputSummary confirm(@Valid @RequestBody ConfirmPluginInputRequest input) {
        return inputs.confirm(input);
    }

    @GetMapping("/sources/{id}/plugin-inputs")
    public List<PluginInputSummary> list(@PathVariable String id) {
        return inputs.list(id);
    }

    @GetMapping("/sources/{id}/plugin-inputs/{inputId}/original")
    public ResponseEntity<byte[]> original(@PathVariable String id, @PathVariable String inputId) {
        return inputs.original(id, inputId);
    }
}
