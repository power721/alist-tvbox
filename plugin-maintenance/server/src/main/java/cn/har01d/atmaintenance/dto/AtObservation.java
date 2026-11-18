package cn.har01d.atmaintenance.dto;

import java.time.Instant;
import java.util.List;

public record AtObservation(String sha256, AtConnection connection, Instant observedAt,
                            List<AtPluginSummary> plugins, String authority, String boundary,
                            List<Integer> excludedPluginIds) {
}
