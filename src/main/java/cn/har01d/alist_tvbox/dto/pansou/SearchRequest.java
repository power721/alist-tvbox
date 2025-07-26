package cn.har01d.alist_tvbox.dto.pansou;

import lombok.Data;

import java.util.List;

@Data
public class SearchRequest {
    private String kw;
    private List<String> channels;
    private String src;
    private String res = "results";

    public SearchRequest() {
    }

    public SearchRequest(String kw, List<String> channels, String src) {
        this.kw = kw;
        this.channels = channels;
        this.src = src;
    }
}
