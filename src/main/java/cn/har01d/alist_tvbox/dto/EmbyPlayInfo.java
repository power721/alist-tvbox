package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.dto.emby.EmbyInfo;
import cn.har01d.alist_tvbox.entity.Emby;
import lombok.Data;

@Data
public class EmbyPlayInfo {

    private final String playing = """
            {
              "VolumeLevel": 100,
              "IsMuted": false,
              "IsPaused": false,
              "RepeatMode": "RepeatNone",
              "Shuffle": false,
              "PlaybackRate": 1,
              "MaxStreamingBitrate": 200000000,
              "PositionTicks": %d,
              "PlaybackStartTimeTicks": %d,
              "PlayMethod": "DirectStream",
              "PlaySessionId": "%s",
              "MediaSourceId": "%s",
              "CanSeek": true,
              "ItemId": "%s"
            }
            """;

    private final String stopped = """
            {
              "VolumeLevel": 100,
              "IsMuted": false,
              "IsPaused": true,
              "RepeatMode": "RepeatNone",
              "Shuffle": false,
              "PlaybackRate": 1,
              "MaxStreamingBitrate": 200000000,
              "PositionTicks": %d,
              "PlaybackStartTimeTicks": %d,
              "PlayMethod": "DirectStream",
              "PlaySessionId": "%s",
              "MediaSourceId": "%s",
              "CanSeek": true,
              "ItemId": "%s"
            }
            """;

    private final String progress = """
            {
              "VolumeLevel": 100,
              "IsMuted": false,
              "IsPaused": false,
              "RepeatMode": "RepeatNone",
              "Shuffle": false,
              "SubtitleOffset": 0,
              "PlaybackRate": 1,
              "MaxStreamingBitrate": 200000000,
              "PositionTicks": %d,
              "PlaybackStartTimeTicks": %d,
              "PlayMethod": "DirectStream",
              "PlaySessionId": "%s",
              "MediaSourceId": "%s",
              "CanSeek": true,
              "ItemId": "%s",
              "EventName": "timeupdate"
            }
            """;

    private final Emby emby;
    private final EmbyInfo info;
    private final String itemId;
    private final String playSessionId;
    private final String mediaSourceId;
    private final long totalTime;

    private final long startTime = System.currentTimeMillis();
    private final long playbackStartTimeTicks = startTime * 10000;
    private long currentTime;

    public String getPlaying() {
        return playing.formatted(currentTime, playbackStartTimeTicks, playSessionId, mediaSourceId, itemId);
    }

    public String getStopped() {
        return stopped.formatted(currentTime, playbackStartTimeTicks, playSessionId, mediaSourceId, itemId);
    }

    public String getProgress(long value) {
        currentTime = value * 10000;
        return progress.formatted(currentTime, playbackStartTimeTicks, playSessionId, mediaSourceId, itemId);
    }
}
