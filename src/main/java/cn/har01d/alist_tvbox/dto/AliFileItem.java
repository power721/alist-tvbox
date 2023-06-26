package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class AliFileItem {
    @JsonProperty("file_id")
    private String fileId;
    private String name;
    private String type;
    private String status;
    @JsonProperty("created_at")
    private Instant createdAt;
    @JsonProperty("updated_at")
    private Instant updatedAt;
    private boolean hidden;
    private long size;
}
