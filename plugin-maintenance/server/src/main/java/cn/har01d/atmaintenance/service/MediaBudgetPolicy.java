package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaBudgetPolicy {
    private final ObjectNode snapshot;
    final int globalProbes, sourceProbes, sourceInterval, hostInterval;
    final long globalRequests, sourceRequests, globalBytes, sourceBytes;

    public MediaBudgetPolicy(ObjectMapper mapper,
            @Value("${maintenance.media.daily-probe-limit:24}") int globalProbes,
            @Value("${maintenance.media.daily-request-limit:288}") long globalRequests,
            @Value("${maintenance.media.daily-retained-bytes:12582912}") long globalBytes,
            @Value("${maintenance.media.source-daily-probe-limit:4}") int sourceProbes,
            @Value("${maintenance.media.source-daily-request-limit:48}") long sourceRequests,
            @Value("${maintenance.media.source-daily-retained-bytes:2097152}") long sourceBytes,
            @Value("${maintenance.media.source-interval-seconds:21600}") int sourceInterval,
            @Value("${maintenance.media.host-interval-seconds:1800}") int hostInterval) {
        if (globalProbes < 1 || globalProbes > 10000 || sourceProbes < 1 || sourceProbes > 10000
                || globalRequests < 1 || globalRequests > 1_000_000 || sourceRequests < 1 || sourceRequests > 1_000_000
                || globalBytes < 1 || globalBytes > 1_099_511_627_776L || sourceBytes < 1 || sourceBytes > 1_099_511_627_776L
                || sourceInterval < 1800 || sourceInterval > 604800 || hostInterval < 1800 || hostInterval > 604800)
            throw new IllegalArgumentException("INVALID_MEDIA_BUDGET_POLICY");
        this.globalProbes = globalProbes; this.globalRequests = globalRequests; this.globalBytes = globalBytes;
        this.sourceProbes = sourceProbes; this.sourceRequests = sourceRequests; this.sourceBytes = sourceBytes;
        this.sourceInterval = sourceInterval; this.hostInterval = hostInterval;
        snapshot = mapper.createObjectNode().put("schemaVersion", 1)
                .put("dailyProbeLimit", globalProbes).put("dailyRequestLimit", globalRequests)
                .put("dailyRetainedBytes", globalBytes).put("sourceDailyProbeLimit", sourceProbes)
                .put("sourceDailyRequestLimit", sourceRequests).put("sourceDailyRetainedBytes", sourceBytes)
                .put("sourceIntervalSeconds", sourceInterval).put("hostIntervalSeconds", hostInterval)
                .put("requestsPerReservation", MediaNetworkPolicy.MAX_REQUESTS)
                .put("retainedBytesPerReservation", MediaNetworkPolicy.MAX_TOTAL_BYTES)
                .put("accounting", "non_refundable_reserved_response_body_capacity_not_wire_bytes")
                .put("hostScope", "lowercase_uri_host_without_scheme_or_port_not_registrable_domain")
                .put("boundary", "仅覆盖本模块媒体任务；不覆盖 AT、爬虫、模型或其他出站客户端。");
    }

    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String sha256() { return Hashes.sha256(snapshot.toString()); }
}
