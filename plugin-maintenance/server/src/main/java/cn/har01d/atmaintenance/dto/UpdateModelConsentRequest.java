package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Pattern;

public record UpdateModelConsentRequest(@Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactSha256,
                                        @Pattern(regexp = "[a-f0-9]{64}") String artifactSha256) {
}
