package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StartAtPublicationRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCandidateSha256,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedBindingSha256,
        @Pattern(regexp = "incremental|replace") String mode,
        @NotBlank @Size(max = 80) String requestKey,
        boolean authorized,
        @Size(min = 1, max = 60)
        @Pattern(regexp = "(?s)(?=.*\\p{IsHan})[^<>:\"/\\\\|?*\\p{Cntrl}]+") String deliveryName) {
}
