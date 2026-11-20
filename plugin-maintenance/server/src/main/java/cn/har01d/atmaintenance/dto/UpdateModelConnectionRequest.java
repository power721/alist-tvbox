package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record UpdateModelConnectionRequest(
        boolean enabled,
        @NotNull @Size(max = 2048) String endpoint,
        @NotNull @Size(max = 2048) String baseUrl,
        @NotNull @Size(max = 120) String model,
        @NotNull @Size(max = 8192) String apiKey,
        boolean clearApiKey,
        @NotNull @Size(max = 101) String keyEnv,
        @Min(100) @Max(120000) int timeoutMillis,
        @Min(128) @Max(16384) int maxCompletionTokens,
        @NotNull String maxTokenParameter,
        @NotNull String jsonMode,
        boolean includeStoreParameter,
        Boolean autoSubmitEnabled) {}
