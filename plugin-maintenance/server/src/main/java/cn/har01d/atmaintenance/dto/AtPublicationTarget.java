package cn.har01d.atmaintenance.dto;

import java.util.List;

public record AtPublicationTarget(boolean available, String blockedReason, String defaultMode,
        String bindingSha256, Integer originalPluginId, String originalPluginName,
        SpiderDelivery delivery, List<AtPublicationSummary> publications) {
}
