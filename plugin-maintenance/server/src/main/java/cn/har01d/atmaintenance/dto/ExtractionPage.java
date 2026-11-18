package cn.har01d.atmaintenance.dto;

import java.util.List;
import java.util.Map;

public record ExtractionPage(String role, String html, String htmlSha256, List<Map<String, String>> expected) {}
