package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

@Data
public class SearchResult {
    private int id;
    private String content;
    private String channel;
    private long time;
}
