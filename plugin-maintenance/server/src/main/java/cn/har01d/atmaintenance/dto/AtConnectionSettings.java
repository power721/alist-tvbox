package cn.har01d.atmaintenance.dto;

public record AtConnectionSettings(String origin, String instanceId, String revision, String authMode,
                                   String credential, boolean allowHttp, String runtimeIdentityPath,
                                   Boolean useEmbedded) {
    public AtConnectionSettings(String origin, String instanceId, String revision, String authMode,
                                String credential, boolean allowHttp, String runtimeIdentityPath) {
        this(origin, instanceId, revision, authMode, credential, allowHttp, runtimeIdentityPath, null);
    }
}
