package cn.har01d.atmaintenance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "maintenance.scheduling.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProbeScheduler {
    private final ProbeScheduleStore schedules;
    private final ProbeService probes;

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void poll() {
        for (String source : schedules.activeSources()) schedules.reconcile(source);
        int submitted = 0;
        for (String source : schedules.dueSources()) {
            var run = schedules.claim(source);
            if (run == null) continue;
            try {
                probes.submit(run);
            } catch (ResponseStatusException error) {
                if (!"QUEUE_FULL".equals(error.getReason())) throw error;
                schedules.reconcile(source);
                break;
            }
            if (++submitted == 4) break;
        }
    }
}
