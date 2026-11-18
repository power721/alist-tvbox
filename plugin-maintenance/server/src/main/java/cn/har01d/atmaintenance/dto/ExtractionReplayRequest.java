package cn.har01d.atmaintenance.dto;

import java.util.List;
import java.util.Map;

public record ExtractionReplayRequest(String expectedArtifactSha256, ExtractionRule rule,
                                      List<ExtractionPage> pages, Map<String, String> dependencySha256) {}
