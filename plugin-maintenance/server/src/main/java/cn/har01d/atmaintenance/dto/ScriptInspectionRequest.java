package cn.har01d.atmaintenance.dto;

import java.util.List;

public record ScriptInspectionRequest(String expectedFaultSha256, String pageUrl, List<String> scriptUrls) {}
