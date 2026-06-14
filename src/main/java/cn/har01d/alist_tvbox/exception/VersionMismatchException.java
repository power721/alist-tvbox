package cn.har01d.alist_tvbox.exception;

public class VersionMismatchException extends RuntimeException {
    private final String localVersion;
    private final String remoteVersion;

    public VersionMismatchException(String localVersion, String remoteVersion) {
        super(String.format("版本不一致：本地 %s，远端 %s", localVersion, remoteVersion));
        this.localVersion = localVersion;
        this.remoteVersion = remoteVersion;
    }

    public String getLocalVersion() {
        return localVersion;
    }

    public String getRemoteVersion() {
        return remoteVersion;
    }
}
