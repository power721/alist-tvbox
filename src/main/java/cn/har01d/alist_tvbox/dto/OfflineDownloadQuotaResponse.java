package cn.har01d.alist_tvbox.dto;

public record OfflineDownloadQuotaResponse(boolean supported, int surplus, int count, String displayText) {
    public OfflineDownloadQuotaResponse(int surplus, int count, int used) {
        this(true, surplus, count, "");
    }
}
