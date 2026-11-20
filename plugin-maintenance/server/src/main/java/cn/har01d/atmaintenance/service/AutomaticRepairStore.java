package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.AutomaticRepairSummary;
import cn.har01d.atmaintenance.entity.RepairSession;
import cn.har01d.atmaintenance.repository.RepairRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AutomaticRepairStore {
    private final RepairRepository repairs;

    @Transactional(readOnly = true)
    public AutomaticRepairSummary get(String id) {
        return summary(repairs.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "REPAIR_NOT_FOUND")));
    }

    @Transactional(readOnly = true)
    public List<String> active() {
        return repairs.findByAutomaticStatus("ACTIVE").stream().map(RepairSession::getId).toList();
    }

    @Transactional
    public AutomaticRepairSummary start(String id, String configurationSha, boolean enhancedDiagnostics) {
        var repair = repairs.findForUpdate(id).orElseThrow();
        if ("ACTIVE".equals(repair.getAutomaticStatus())) {
            if (!configurationSha.equals(repair.getAutomaticConfigurationSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "MODEL_CONFIGURATION_CHANGED");
            if (enhancedDiagnostics != repair.isAutomaticEnhancedDiagnostics())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "AUTOMATIC_DIAGNOSTIC_MODE_CHANGED");
        } else {
            repair.setAutomaticConfigurationSha256(configurationSha);
            repair.setAutomaticEnhancedDiagnostics(enhancedDiagnostics);
            repair.setAutomaticStatus("ACTIVE");
            repair.setAutomaticStopCode(null);
        }
        return summary(repair);
    }

    @Transactional
    public AutomaticRepairSummary finish(String id, String status, String code) {
        var repair = repairs.findForUpdate(id).orElseThrow();
        if ("ACTIVE".equals(repair.getAutomaticStatus())) {
            repair.setAutomaticStatus(status);
            repair.setAutomaticStopCode(code);
        }
        return summary(repair);
    }

    private AutomaticRepairSummary summary(RepairSession repair) {
        return new AutomaticRepairSummary(repair.getId(), repair.getAutomaticStatus(),
                repair.getAutomaticConfigurationSha256(), repair.getAutomaticStopCode(),
                repair.isAutomaticEnhancedDiagnostics());
    }
}
