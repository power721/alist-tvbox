package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.EmbeddedDeploymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance/at-native")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class EmbeddedDeploymentController {
    private final EmbeddedDeploymentService deployments;

    @GetMapping("/release-plans/{planId}/target")
    public ResponseEntity<EmbeddedDeploymentTarget> target(@PathVariable String planId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.target(planId));
    }

    @PostMapping("/plugins/{pluginId}/deployments")
    public ResponseEntity<EmbeddedDeploymentSummary> deploy(@PathVariable int pluginId,
            @RequestBody @Valid StartEmbeddedDeploymentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.deploy(pluginId, request));
    }
    @PostMapping("/deployments/{id}/rollback")
    public ResponseEntity<EmbeddedDeploymentSummary> rollback(@PathVariable String id,
            @RequestBody @Valid RollbackEmbeddedDeploymentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.rollback(id, request));
    }
    @GetMapping("/deployments/{id}")
    public ResponseEntity<EmbeddedDeploymentSummary> get(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.get(id));
    }
    @GetMapping("/plugins/{pluginId}/deployments")
    public ResponseEntity<List<EmbeddedDeploymentSummary>> list(@PathVariable int pluginId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.list(pluginId));
    }

    @GetMapping("/plugins/{pluginId}/saved-runtime")
    public ResponseEntity<AtSavedRuntimeTarget> savedRuntime(@PathVariable int pluginId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.savedRuntime(pluginId));
    }

    @PostMapping("/plugins/{pluginId}/activations")
    public ResponseEntity<AtRuntimeActivation> activate(@PathVariable int pluginId,
            @RequestBody @Valid StartAtRuntimeActivationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.activate(pluginId, request));
    }

    @PostMapping("/activations/{id}/resume")
    public ResponseEntity<AtRuntimeActivation> resumeActivation(@PathVariable String id,
            @RequestBody @Valid RollbackEmbeddedDeploymentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deployments.resumeActivation(id, request));
    }
}
