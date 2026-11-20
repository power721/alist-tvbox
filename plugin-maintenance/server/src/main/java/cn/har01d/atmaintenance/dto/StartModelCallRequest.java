package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record StartModelCallRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFaultSha256,
                                    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedConfigurationSha256,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String requestKey,
                                    boolean autoSubmit, boolean enhancedDiagnostics) {
    public StartModelCallRequest(String expectedFaultSha256, String expectedConfigurationSha256,
                                 String requestKey, boolean autoSubmit) {
        this(expectedFaultSha256, expectedConfigurationSha256, requestKey, autoSubmit, false);
    }
    public StartModelCallRequest(String expectedFaultSha256, String expectedConfigurationSha256, String requestKey) {
        this(expectedFaultSha256, expectedConfigurationSha256, requestKey, false);
    }
}
