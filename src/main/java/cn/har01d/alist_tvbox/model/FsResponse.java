package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FsResponse {
    private String provider;
    private int total;
    private boolean write;
    private List<FsInfo> content = new ArrayList<>();
    private List<FsInfo> files = new ArrayList<>();
}
