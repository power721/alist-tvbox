package cn.har01d.atmaintenance.dto;

import java.util.List;

public record AtSavedRuntimeTarget(boolean enabled, int pluginId, String pluginSha256,
                                   String configurationSha256, AtRuntimeIdentity runtime,
                                   List<AtRuntimeActivation> activations) {}
