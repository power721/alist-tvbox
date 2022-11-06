package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.List;

@Data
public class FsResponse {
    private String provider;
    private String readme;
    private int total;
    private boolean write;
    private List<FsInfo> content;
}
