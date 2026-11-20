package cn.har01d.atmaintenance.dto;

public record ReverseAnalysisRequest(String expectedFaultSha256, String pageUrl,
                                     String requestPattern, boolean recoverScripts) {}
