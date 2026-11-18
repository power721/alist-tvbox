package cn.har01d.atmaintenance.dto;

public record PageDiagnosticsRequest(String expectedArtifactSha256, String pageUrl) {
}
