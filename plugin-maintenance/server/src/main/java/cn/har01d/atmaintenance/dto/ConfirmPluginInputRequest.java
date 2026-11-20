package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConfirmPluginInputRequest(
        @NotBlank @Size(max = 1_398_104) String inputBase64,
        @AssertTrue boolean authorized,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedEvidenceSha256,
        @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String requestKey,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) @Pattern(regexp = "[^/\\\\]+\\.py") String filename) {
}
