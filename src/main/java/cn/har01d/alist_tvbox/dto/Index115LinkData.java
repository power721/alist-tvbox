package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Index115LinkData {
    private String url;
    @JsonProperty("expired_in") private long expiredIn;
}
