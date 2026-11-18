package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ImportedCandidateRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String importId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArchiveSha256) {
}
