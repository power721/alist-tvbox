package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record RenameAtPublicationRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedCandidateSha256,
        @NotBlank @Size(max = 200) String expectedPluginName,
        @NotBlank @Size(max = 60)
        @Pattern(regexp = "(?s)(?=.*\\p{IsHan})[^<>:\"/\\\\|?*\\p{Cntrl}]+") String deliveryName,
        boolean authorized) {
}
