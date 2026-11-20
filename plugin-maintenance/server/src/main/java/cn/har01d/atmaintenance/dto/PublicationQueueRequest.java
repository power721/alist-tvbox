package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PublicationQueueRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCandidateSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedBindingSha256,
        @NotBlank @Pattern(regexp = "incremental|replace") String mode,
        boolean authorized) {
}
