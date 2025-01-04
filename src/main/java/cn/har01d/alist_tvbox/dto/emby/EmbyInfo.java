package cn.har01d.alist_tvbox.dto.emby;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmbyInfo {
    @JsonProperty("AccessToken")
    private String accessToken;

    @JsonProperty("User")
    private User user;

    @JsonProperty("SessionInfo")
    private SessionInfo sessionInfo;

    private List<EmbyItem> views = new ArrayList<>();

    @Data
    public static class User {
        @JsonProperty("Id")
        private String id;
    }

    @Data
    public static class SessionInfo {
        @JsonProperty("Client")
        private String client;

        @JsonProperty("DeviceId")
        private String deviceId;

        @JsonProperty("DeviceName")
        private String deviceName;

        @JsonProperty("ApplicationVersion")
        private String version;
    }
}
