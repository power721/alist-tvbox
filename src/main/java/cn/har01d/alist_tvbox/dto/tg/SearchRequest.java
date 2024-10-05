package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

@Data
public class SearchRequest {
    private String keyword;
    private String channelUsername;
    private String encode;
    private String page;
}
