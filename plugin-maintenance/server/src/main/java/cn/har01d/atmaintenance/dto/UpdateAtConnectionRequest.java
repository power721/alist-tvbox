package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateAtConnectionRequest(
        @NotNull @Size(max = 2048) String origin,
        @NotNull @Size(max = 100) String instanceId,
        @NotNull @Size(max = 200) String revision,
        @NotNull @Size(max = 20) String authMode,
        @NotNull @Size(max = 8192) String credential,
        boolean clearCredential, boolean allowHttp, Boolean useEmbedded) {
    public UpdateAtConnectionRequest(String origin, String instanceId, String revision, String authMode,
                                     String credential, boolean clearCredential, boolean allowHttp) {
        this(origin, instanceId, revision, authMode, credential, clearCredential, allowHttp, null);
    }
}
