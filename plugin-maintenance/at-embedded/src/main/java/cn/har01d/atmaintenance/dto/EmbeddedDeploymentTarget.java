package cn.har01d.atmaintenance.dto;

import java.util.List;

public record EmbeddedDeploymentTarget(boolean enabled, int pluginId, String pluginSha256,
                                       AtRuntimeIdentity runtime, List<EmbeddedDeploymentSummary> deployments) {}
