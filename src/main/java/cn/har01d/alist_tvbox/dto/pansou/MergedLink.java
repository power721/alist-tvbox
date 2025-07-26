package cn.har01d.alist_tvbox.dto.pansou;

import lombok.Data;

import java.time.Instant;

@Data
public class MergedLink {
    private String url;
    private String password;
    private String note;
    private Instant datetime;
}
