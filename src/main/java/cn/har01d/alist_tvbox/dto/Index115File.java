package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Index115File {
    @JsonProperty("FileID") private String fileId;
    @JsonProperty("ShareCode") private String shareCode;
    @JsonProperty("ReceiveCode") private String receiveCode;
    @JsonProperty("Name") private String name;
    @JsonProperty("Path") private String path;
    @JsonProperty("Size") private long size;
    @JsonProperty("IsDir") private boolean dir;
    @JsonProperty("Ext") private String ext;
}
