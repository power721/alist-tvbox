package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AliyundriveOpen extends Storage<AliyundriveOpen.Addition> {
    public static class Addition {
        private String drive_type = "resource";
        private String refresh_token;
        private String root_folder_id;
        private String order_by;
        private String order_direction;
        private String oauth_token_url;
        private String client_id;
        private String client_secret;
        private String remove_way;
        private String livp_download_format = "mov";
        @JsonProperty("AccessToken")
        private String accessToken;
        private boolean rapid_upload;
        private boolean internal_upload;
        private boolean master;
    }
}
