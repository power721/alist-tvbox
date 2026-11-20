package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConfirmSourceImportRequest(
        @NotBlank @Size(max = 5_592_408) String archiveBase64,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArchiveSha256,
        @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String requestKey,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) @Pattern(regexp = "[^/\\\\]+\\.py") String filename) {
}
