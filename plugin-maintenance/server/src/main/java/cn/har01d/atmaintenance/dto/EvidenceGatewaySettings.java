package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record EvidenceGatewaySettings(boolean enabled,
        @NotNull @Pattern(regexp = "trawl|flaresolverr|flaresolverr-rs-alt") String adapter,
        @NotNull @Size(max = 2048) String endpoint,
        @Min(1000) @Max(60000) int timeoutMillis) {
    public static EvidenceGatewaySettings disabled() {
        return new EvidenceGatewaySettings(false, "trawl", "", 30000);
    }
}
