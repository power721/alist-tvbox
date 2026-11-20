package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateSourceCredentialsRequest(
        @NotNull String expectedRevision, boolean required,
        @NotNull @Size(max = 2048) String origin,
        @NotNull @Size(max = 8192) String cookie, boolean clearCookie,
        @NotNull @Size(max = 8192) String authorization, boolean clearAuthorization) {}
