package cn.har01d.alist_tvbox.dto.playback;

import lombok.Data;

import java.util.Map;

/**
 * 多端播放记录同步:规范化的单条 upsert 记录。
 * <p>
 * 三种入站形态由 {@link #fromMap(Map)} 收敛:
 * <ul>
 *   <li>FongMi 原生 History:{@code key/vodName/position/duration/createTime/cid}</li>
 *   <li>webhtv PlaybackRecord:{@code historyKey/siteKey/vodId/positionMs/durationMs/updatedAt}</li>
 *   <li>atv-player media_playback_history:{@code sourceKind/sourceKey/vodId/position/duration/updated_at}</li>
 * </ul>
 * 不使用 @JsonAlias(项目惯例),别名在此手动映射;FongMi 的复合 key 按 "@@@" 拆出 siteKey/vodId。
 */
@Data
public class PlaybackSyncInput {
    private String sourceKind;
    private String sourceKey;
    private String sourceName;
    private String vodId;
    private String vodName;
    private String vodPic;
    private String vodFlag;
    private String episodeName;
    private Integer episode;
    private String episodeUrl;
    private long positionMs;
    private long durationMs;
    private long openingMs;
    private long endingMs;
    private float speed = 1f;
    private boolean completed;
    private long updatedAt;
    private String clientKey;
    private Integer playlistIndex;
    private Integer sourceGroupIndex;
    private Integer sourceIndex;
    private Integer sourceSubgroupIndex;
    private String sourceSubgroupName;
    private String driveDirId;
    private String driveShareKey;
    private String drivePath;
    private String event;

    /** webhtv 客户端按 siteKey 读取来源标识;序列化时随 sourceKey 一并输出,兼容 Fish/默影视。 */
    public String getSiteKey() {
        return sourceKey;
    }

    public static PlaybackSyncInput fromMap(Map<String, Object> m) {
        PlaybackSyncInput in = new PlaybackSyncInput();
        if (m == null) {
            return in;
        }
        in.sourceKind = str(m, "sourceKind", "source_kind");
        in.sourceKey = str(m, "sourceKey", "source_key", "siteKey", "site_key", "site", "interfaceKey");
        in.sourceName = str(m, "sourceName", "source_name", "siteName", "site_name", "configName", "config_name");
        in.vodId = str(m, "vodId", "vod_id", "videoId", "itemId");
        in.vodName = str(m, "vodName", "vod_name", "name", "title");
        in.vodPic = str(m, "vodPic", "vod_pic", "pic", "poster", "artwork");
        in.vodFlag = str(m, "vodFlag", "vod_flag", "flag", "line");
        in.episodeName = str(m, "episodeName", "episode_name", "episodeTitle", "episode_title",
                "vodRemarks", "vod_remarks", "remarks", "artist");
        in.episode = intg(m, "episode", "episodeIndex", "episode_index");
        in.episodeUrl = str(m, "episodeUrl", "episode_url", "url", "playUrl", "play_url");
        in.positionMs = lng(m, "positionMs", "position_ms", "position", "pos");
        in.durationMs = lng(m, "durationMs", "duration_ms", "duration");
        in.openingMs = lng(m, "openingMs", "opening_ms", "opening");
        in.endingMs = lng(m, "endingMs", "ending_ms", "ending");
        in.speed = (float) dbl(m, 1.0, "speed");
        in.completed = bool(m, "completed");
        in.updatedAt = lng(m, "updatedAt", "updated_at", "timestamp", "updateTime", "update_time",
                "createTime", "create_time");
        in.clientKey = str(m, "clientKey", "client_key");
        in.playlistIndex = intg(m, "playlistIndex", "playlist_index");
        in.sourceGroupIndex = intg(m, "sourceGroupIndex", "source_group_index");
        in.sourceIndex = intg(m, "sourceIndex", "source_index");
        in.sourceSubgroupIndex = intg(m, "sourceSubgroupIndex", "source_subgroup_index");
        in.sourceSubgroupName = str(m, "sourceSubgroupName", "source_subgroup_name");
        in.driveDirId = str(m, "driveDirId", "drive_dir_id");
        in.driveShareKey = str(m, "driveShareKey", "drive_share_key");
        in.drivePath = str(m, "drivePath", "drive_path");
        in.event = str(m, "event");

        // FongMi 复合 key: siteKey@@@vodId@@@cid —— 拆出 sourceKey/vodId
        String key = str(m, "key", "historyKey", "history_key");
        if (key != null && key.contains("@@@")) {
            String[] parts = key.split("@@@", -1);
            if (parts.length >= 2) {
                if (in.sourceKey == null) {
                    in.sourceKey = parts[0];
                }
                if (in.vodId == null) {
                    in.vodId = parts[1];
                }
            }
        }
        // 默认来源类型:带 siteKey/复合key 的视为站点源
        if (in.sourceKind == null && (in.sourceKey != null || key != null)) {
            in.sourceKind = "site";
        }
        // 已看完:ended 事件或进度 ≥ 0.95
        if ("ended".equals(eventName(in.event)) && in.durationMs > 0) {
            in.completed = true;
        }
        if (!in.completed && in.durationMs > 0 && in.positionMs > 0
                && (double) in.positionMs / in.durationMs >= 0.95) {
            in.completed = true;
        }
        return in;
    }

    /** 取 event 最后一个 '.' 段(playback.progress → progress),便于宽松比较。 */
    public static String eventName(String event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        int idx = event.lastIndexOf('.');
        return idx >= 0 ? event.substring(idx + 1) : event;
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
                try {
                    return (long) Double.parseDouble(s.trim());
                } catch (Exception e2) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /** 返回 Integer:缺省集数用 null 表示"未提供",返回 int 会在自动拆箱时 NPE。 */
    private static Integer intg(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static double dbl(Map<String, Object> m, double def, String... keys) {
        Object v = first(m, keys);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    private static boolean bool(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return false;
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
