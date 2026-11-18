package cn.har01d.atmaintenance.dto;

public record AtConnection(boolean configured, String instanceId, String instanceKey,
                           String originSha256, String declaredRevision, String authority) {
}
