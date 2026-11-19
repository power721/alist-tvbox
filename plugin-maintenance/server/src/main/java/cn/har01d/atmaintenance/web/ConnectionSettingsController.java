package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.UpdateAtConnectionRequest;
import cn.har01d.atmaintenance.dto.UpdateModelConnectionRequest;
import cn.har01d.atmaintenance.dto.EvidenceGatewaySettings;
import cn.har01d.atmaintenance.service.ConnectionSettingsService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plugin-maintenance/settings/connections")
@RequiredArgsConstructor
public class ConnectionSettingsController {
    private final ConnectionSettingsService service;

    @GetMapping
    public ObjectNode get() { return service.view(); }

    @PutMapping("/at")
    public ObjectNode at(@Valid @RequestBody UpdateAtConnectionRequest input) { return service.updateAt(input); }

    @PutMapping("/model")
    public ObjectNode model(@Valid @RequestBody UpdateModelConnectionRequest input) { return service.updateModel(input); }

    @PutMapping("/evidence-gateway")
    public ObjectNode gateway(@Valid @RequestBody EvidenceGatewaySettings input) { return service.updateEvidenceGateway(input); }

    @GetMapping("/evidence-gateway/health")
    public ObjectNode gatewayHealth() { return service.checkEvidenceGateway(); }

    @PutMapping("/model-limits")
    public ObjectNode limits(@RequestBody cn.har01d.atmaintenance.dto.ModelLimits input) { return service.updateLimits(input); }
}
