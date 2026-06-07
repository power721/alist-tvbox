package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderSyncResponse(int queued,
                                     int skipped,
                                     Map<Long, TgProviderSyncResult> results,
                                     Map<Long, String> failures) {
    public static TgProviderSyncResponse empty() {
        return new TgProviderSyncResponse(0, 0, Map.of(), Map.of());
    }
}
