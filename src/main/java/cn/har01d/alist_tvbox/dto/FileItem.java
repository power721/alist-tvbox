package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class FileItem {
    private final String name;
    private final String path;
    private final int type;
}
