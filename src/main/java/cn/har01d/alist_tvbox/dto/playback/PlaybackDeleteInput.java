package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

import java.util.Map;

/**
 * 多端播放记录同步:删除标记。对应 webhtv 的 deleted[] / playback.deleted 事件。
 * scope: all(需确认)/ site(按 sourceKey)/ item(默认,按 sourceKey+vodId)。
 */
@Data
public class PlaybackDeleteInput {
    private String scope = "item";
    private String sourceKind;
    private String sourceKey;
    private String vodId;
    private String historyKey;
    private long deletedAt;

    /** webhtv 客户端按 siteKey 读取来源标识;序列化时随 sourceKey 一并输出,兼容 Fish/默影视。 */
    public String getSiteKey() {
        return sourceKey;
    }

    public static PlaybackDeleteInput fromMap(Map<String, Object> m) {
        PlaybackDeleteInput d = new PlaybackDeleteInput();
        if (m == null) {
            return d;
        }
        d.scope = str(m, "scope");
        if (d.scope == null) {
            d.scope = "item";
        }
        d.sourceKind = str(m, "sourceKind", "source_kind");
        d.sourceKey = str(m, "sourceKey", "source_key", "siteKey", "site_key", "site");
        d.vodId = str(m, "vodId", "vod_id", "videoId", "itemId");
        d.historyKey = str(m, "historyKey", "history_key", "key");
        d.deletedAt = lng(m, "deletedAt", "deleted_at", "timestamp", "updatedAt", "updated_at",
                "createTime", "create_time");

        String key = d.historyKey;
        if (key != null && key.contains("@@@")) {
            String[] parts = key.split("@@@", -1);
            if (parts.length >= 2) {
                if (d.sourceKey == null) {
                    d.sourceKey = parts[0];
                }
                if (d.vodId == null) {
                    d.vodId = parts[1];
                }
            }
        }
        if (d.sourceKind == null && d.sourceKey != null) {
            d.sourceKind = "site";
        }
        return d;
    }

    private static String str(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static long lng(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static Object first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }
}
