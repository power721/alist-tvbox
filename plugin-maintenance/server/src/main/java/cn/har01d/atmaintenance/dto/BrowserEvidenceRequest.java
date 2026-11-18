package cn.har01d.atmaintenance.dto;

import java.util.List;

public record BrowserEvidenceRequest(String jobId, String expectedArtifactSha256, String frameId,
                                     String frameUrlSha256, String selector, boolean requireFocus,
                                     List<String> responseUrls, int maxBodyBytes, boolean allowResponseAbort) {}
